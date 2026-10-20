"""Validate exclusive work ownership and render the two implementation queues."""


def assignments(plan):
    return {task['wp']: dict(task, owner=agent['id'], reviewer=agent['reviewer'],
                            ticket=f"{agent['id']}-{index:02d}")
            for agent in plan['execution_plan']['agents']
            for index, task in enumerate(agent['backlog'], 1)}


def validate_assignments(plan):
    errors = []
    execution = plan.get('execution_plan', {})
    agents = execution.get('agents', [])
    if sorted(a['id'] for a in agents) != ['Claude', 'GPT']:
        return ['execution plan requires exactly GPT and Claude']
    packages = {w['id']: w for w in plan['work_packages']}
    unfinished = {i for i, w in packages.items() if w['progress']['status'] != 'completed'}
    baseline = set(execution.get('baseline_dependencies', []))
    if baseline != {'WP-105'}:
        errors.append('only the evidenced WP-105 core may bypass an unfinished start dependency')
    seen, tasks = set(), {}
    for agent in agents:
        if agent['reviewer'] not in {'GPT', 'Claude'} - {agent['id']}:
            errors.append(f"{agent['id']}: reviewer must be the other agent")
        for task in agent['backlog']:
            ident = task['wp']
            if ident in seen:
                errors.append(f'{ident}: duplicate implementation owner')
            seen.add(ident)
            tasks[ident] = task
            if ident not in unfinished:
                errors.append(f'{ident}: backlog must contain only unfinished known packages')
                continue
            if not task.get('outcome') or not task.get('handoff'):
                errors.append(f'{ident}: task needs an outcome and a handoff boundary')
            after = task.get('after', [])
            if len(after) != len(set(after)) or not set(after) <= unfinished:
                errors.append(f'{ident}: invalid or duplicate prerequisite gate')
            required = set(packages[ident]['depends_on']) & unfinished - baseline
            if not required <= set(after):
                errors.append(f'{ident}: missing unfinished prerequisite gates: {sorted(required - set(after))}')
    if unfinished - seen:
        errors.append(f'unassigned unfinished packages: {sorted(unfinished - seen)}')
    visited, active = set(), set()

    def visit(ident):
        if ident in active:
            errors.append(f'{ident}: execution dependency cycle')
            return
        if ident in visited or ident not in tasks:
            return
        active.add(ident)
        for dep in tasks[ident].get('after', []):
            visit(dep)
        active.remove(ident)
        visited.add(ident)

    for ident in tasks:
        visit(ident)
    paths = []
    for area in execution.get('write_areas', []):
        if area['owner'] not in {'GPT', 'Claude'} or not area.get('paths') or not area.get('rule'):
            errors.append('write area needs one registered owner, paths and a handoff rule')
        for path in area['paths']:
            # Require one portable literal spelling so aliases cannot evade exclusive ownership.
            parts = path.rstrip('/').split('/') if isinstance(path, str) else []
            if (not parts or any(part in {'', '.', '..'} or part.endswith((' ', '.')) for part in parts)
                    or path != path.strip() or '\\' in path or ':' in path
                    or any(ord(c) < 32 or ord(c) == 127 or c in '*?[' for c in path)
                    or path.endswith('//')):
                errors.append(f'invalid write area path: {path}')
                continue
            for previous, owner in paths:
                if (path.rstrip('/') == previous.rstrip('/') or (previous.endswith('/') and path.startswith(previous))
                        or (path.endswith('/') and previous.startswith(path))):
                    errors.append(f'overlapping write areas: {owner}:{previous} and {area["owner"]}:{path}')
            paths.append((path, area['owner']))
    if not paths:
        errors.append('execution plan needs exclusive write areas')
    return errors


def agent_backlogs_md(plan):
    packages = {w['id']: w for w in plan['work_packages']}
    owned = assignments(plan)
    execution = plan['execution_plan']
    out = ['## Two-agent delivery plan', '', execution['status'] + '.', '',
           f"Assignment baseline: `{execution['baseline_commit']}` ({execution['date']}).", '',
           'Each backlog below owns the remaining scope of its packages; '
           'the detailed checklists later in this file remain the acceptance contract. Priority numbers can shift '
           'when completed rows are removed; use the stable WP ID in branches and handoffs.', '',
           '### Start here', '',
           '- GPT and Claude may start their first owned packages concurrently on the merged baseline APIs. '
           'WP-108 does not require a WP-102 design handoff.', '',
           '### Working and review agreement', '']
    for agent in execution['agents']:
        if agent['backlog']:
            task = agent['backlog'][0]
            out.insert(out.index('### Working and review agreement') - 1,
                       f"- [ ] **{agent['id']}:** {task['wp']} — {task['outcome']}")
    out += [f'{i}. {rule}' for i, rule in enumerate(execution['rules'], 1)]
    for agent in execution['agents']:
        out += ['', f"### {agent['id']} backlog — {len(agent['backlog'])} packages", '',
                f"Owner: **{agent['id']}**. Reviewer: **{agent['reviewer']}**. {agent['focus']}.", '',
                '| Priority / work package | Start after merged handoffs | Deliver and verify | Integration boundary |',
                '|---|---|---|---|']
        for task in agent['backlog']:
            w = packages[task['wp']]
            gates = ', '.join(f"{dep} ({owned[dep]['owner']})" for dep in task['after'])
            gates = gates or 'Merged baseline APIs; no WP-102 design prerequisite'
            escape = lambda s: str(s).replace('|', '\\|').replace('\n', ' ')
            out.append(f"| [ ] **{owned[w['id']]['ticket']} / {w['id']}** {escape(w['title'])} "
                       f"({w['progress']['status']}) | {gates} | {escape(task['outcome'])} | {escape(task['handoff'])} |")
    out += ['', '### Exclusive write areas', '',
            'Prefixes ending in `/` include their descendants. Paths without a trailing slash are exact files. '
            'New paths must be registered with one writer before parallel work starts. Unlisted paths stay with '
            'GPT integration ownership until reassigned; ownership is a coordination rule, not a GitHub permission. '
            'CI validates assignment coverage, reciprocal reviewers, dependency cycles and overlapping registered areas.', '',
            '| Area / writer | Reserved paths | Handoff rule |', '|---|---|---|']
    for area in execution['write_areas']:
        out.append(f"| {area['area']} — **{area['owner']}** | "
                   + '<br>'.join(f'`{path}`' for path in area['paths']) + f" | {area['rule']} |")
    return out
