"""WP-102 evidence: builds the realm of docs/identity.md section 9 on a running Keycloak and probes token exchange.

Usage:  python3 -I WP-102-keycloak-probe.py BASE_URL            (Keycloak 26.2+, admin/admin bootstrap user)
        MAYACT=1 python3 -I WP-102-keycloak-probe.py BASE_URL   (adds a may_act claim, for 26.8 delegation)

Start Keycloak as in section 9. For delegation add: --features=token-exchange-delegation,parameterized-scopes
It deletes and recreates the realm "agentic". Prints what the server does; asserts nothing about it.
Not a CI test: CI wiring is requested in docs/handoffs/claude/WP-102.md.
"""
import base64, json, sys, time, urllib.parse, urllib.request, urllib.error

BASE = sys.argv[1].rstrip('/')
REALM = 'agentic'
AGENT_SECRET = 's3cret-for-oa-agent-runtime'


def http(method, url, data=None, headers=None, form=False, auth=None):
    headers = dict(headers or {})
    body = None
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        else:
            body = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
    if auth:
        headers['Authorization'] = 'Basic ' + base64.b64encode(f'{auth[0]}:{auth[1]}'.encode()).decode()
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw and raw[:1] in b'{[' else raw.decode())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode()


def payload(token):
    p = token.split('.')[1]
    p += '=' * (-len(p) % 4)
    return json.loads(base64.urlsafe_b64decode(p))


s, b = http('POST', f'{BASE}/realms/master/protocol/openid-connect/token',
            {'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin', 'password': 'admin'}, form=True)
H = {'Authorization': 'Bearer ' + b['access_token']}


def adm(method, path, data=None):
    return http(method, f'{BASE}/admin/realms{path}', data, H)


def must(label, res, ok=(200, 201, 204)):
    assert res[0] in ok, (label, res)
    return res


adm('DELETE', f'/{REALM}')
must('realm', adm('POST', '', {'realm': REALM, 'enabled': True, 'accessTokenLifespan': 3600}))
s, profile = adm('GET', f'/{REALM}/users/profile')
for a in ('tenant', 'sub_kind'):
    profile['attributes'].append({'name': a, 'displayName': a, 'permissions': {'view': ['admin', 'user'], 'edit': ['admin']},
                                  'validations': {}})
must('profile', adm('PUT', f'/{REALM}/users/profile', profile))
for scope in ('cases:read', 'cases:write', 'cases:delete'):
    must(scope, adm('POST', f'/{REALM}/client-scopes', {
        'name': scope, 'protocol': 'openid-connect',
        'attributes': {'include.in.token.scope': 'true', 'display.on.consent.screen': 'false'}}))
s, scopes = adm('GET', f'/{REALM}/client-scopes')
SID = {x['name']: x['id'] for x in scopes}


def user_attr_mapper(claim):
    return {'name': claim, 'protocol': 'openid-connect', 'protocolMapper': 'oidc-usermodel-attribute-mapper',
            'config': {'user.attribute': claim, 'claim.name': claim, 'jsonType.label': 'String',
                       'access.token.claim': 'true', 'id.token.claim': 'false', 'userinfo.token.claim': 'false'}}


def aud_mapper(client_id):
    return {'name': 'aud-' + client_id, 'protocol': 'openid-connect', 'protocolMapper': 'oidc-audience-mapper',
            'config': {'included.client.audience': client_id, 'access.token.claim': 'true', 'id.token.claim': 'false'}}


def make_client(client_id, mappers=(), optional=(), drop_defaults=(), **kw):
    body = {'clientId': client_id, 'enabled': True, 'protocol': 'openid-connect'}
    body.update(kw)
    must(client_id, adm('POST', f'/{REALM}/clients', body))
    s, found = adm('GET', f'/{REALM}/clients?clientId={client_id}')
    uuid = found[0]['id']
    for m in mappers:
        must('mapper', adm('POST', f'/{REALM}/clients/{uuid}/protocol-mappers/models', m))
    for name in optional:
        must('optional', adm('PUT', f'/{REALM}/clients/{uuid}/optional-client-scopes/{SID[name]}'))
    s, defaults = adm('GET', f'/{REALM}/clients/{uuid}/default-client-scopes')
    for d in defaults:
        if d['name'] in drop_defaults:
            must('drop default', adm('DELETE', f'/{REALM}/clients/{uuid}/default-client-scopes/{d["id"]}'))
    return uuid


api_mappers = [aud_mapper('oa-api'), aud_mapper('oa-agent-runtime'), user_attr_mapper('tenant'), user_attr_mapper('sub_kind')]
common = dict(publicClient=True, directAccessGrantsEnabled=True, standardFlowEnabled=False)
make_client('oa-api', api_mappers, optional=('cases:read', 'cases:write'), **common)
# a user-token client with a very short lifespan, to test expiry and "outliving the user's token"
make_client('oa-api-short', api_mappers, optional=('cases:read', 'cases:write'),
            attributes={'access.token.lifespan': '20'}, **common)
agent = make_client('oa-agent-runtime', [user_attr_mapper('tenant'), user_attr_mapper('sub_kind')],
                    optional=('cases:read', 'cases:write'), drop_defaults=('profile', 'email'),
                    publicClient=False, serviceAccountsEnabled=True, secret=AGENT_SECRET, standardFlowEnabled=False,
                    attributes={'standard.token.exchange.enabled': 'true', 'access.token.lifespan': '300'})
for t in ('tool-cases', 'tool-search'):
    make_client(t, publicClient=False, standardFlowEnabled=False)

# service account user of the agent client: its own tenant and kind are USER ATTRIBUTES, so they appear only
# when the token's subject is that user, not on tokens that merely pass through the agent client
s, sa = adm('GET', f'/{REALM}/clients/{agent}/service-account-user')
sa_id = sa['id']
sa['attributes'] = {'tenant': ['tenant-agent'], 'sub_kind': ['AGENT']}
must('sa attrs', adm('PUT', f'/{REALM}/users/{sa_id}', sa))

import os
if os.environ.get('MAYACT'):
    for cid in ('oa-api', 'oa-api-short'):
        s_, f_ = adm('GET', f'/{REALM}/clients?clientId={cid}')
        must('may_act', adm('POST', f'/{REALM}/clients/{f_[0]["id"]}/protocol-mappers/models', {
            'name': 'may_act', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-hardcoded-claim-mapper',
            'config': {'claim.name': 'may_act', 'claim.value': json.dumps({'sub': sa_id}), 'jsonType.label': 'JSON',
                       'access.token.claim': 'true', 'id.token.claim': 'false', 'userinfo.token.claim': 'false'}}))
must('alice', adm('POST', f'/{REALM}/users', {
    'username': 'alice', 'enabled': True, 'emailVerified': True, 'email': 'alice@example.org', 'firstName': 'Alice',
    'lastName': 'Example', 'attributes': {'tenant': ['tenant-1']},
    'credentials': [{'type': 'password', 'value': 'alice-pw', 'temporary': False}]}))
s, found = adm('GET', f'/{REALM}/users?username=alice&exact=true')
alice = found[0]['id']
for t in ('tool-cases', 'tool-search'):
    s, c = adm('GET', f'/{REALM}/clients?clientId={t}')
    must('role', adm('POST', f'/{REALM}/clients/{c[0]["id"]}/roles', {'name': 'invoke'}))
    s, role = adm('GET', f'/{REALM}/clients/{c[0]["id"]}/roles/invoke')
    must('grant', adm('POST', f'/{REALM}/users/{alice}/role-mappings/clients/{c[0]["id"]}', [role]))

TOKEN = f'{BASE}/realms/{REALM}/protocol/openid-connect/token'


def user_token(client='oa-api'):
    s, b = http('POST', TOKEN, {'grant_type': 'password', 'client_id': client, 'username': 'alice', 'password': 'alice-pw',
                                'scope': 'cases:read cases:write'}, form=True)
    assert s == 200, b
    return b['access_token']


def workload_token():
    s, b = http('POST', TOKEN, {'grant_type': 'client_credentials'}, form=True, auth=('oa-agent-runtime', AGENT_SECRET))
    assert s == 200, b
    return b['access_token']


keep = ('sub', 'aud', 'azp', 'scope', 'tenant', 'sub_kind', 'act', 'may_act', 'preferred_username')


def show(p):
    return json.dumps({k: p[k] for k in keep if k in p})


def exchange(label, subject, **extra):
    form = {'grant_type': 'urn:ietf:params:oauth:grant-type:token-exchange', 'subject_token': subject,
            'subject_token_type': 'urn:ietf:params:oauth:token-type:access_token',
            'requested_token_type': 'urn:ietf:params:oauth:token-type:access_token'}
    form.update(extra)
    st, body = http('POST', TOKEN, form, form=True, auth=('oa-agent-runtime', AGENT_SECRET))
    print(f'\n-- {label}: HTTP {st}')
    if st == 200:
        p = payload(body['access_token'])
        print('   keys:', sorted(body), '| expires_in', body.get('expires_in'), '| exp-iat', p['exp'] - p['iat'])
        print('   claims:', show(p))
        return st, body, p
    print('   body:', json.dumps(body) if not isinstance(body, str) else body)
    return st, body, None


U = user_token()
UP = payload(U)
W = workload_token()
WP = payload(W)
print('alice  token:', show(UP), '| lifetime', UP['exp'] - UP['iat'])
print('agent  token:', show(WP), '| lifetime', WP['exp'] - WP['iat'])
ACT = {'actor_token': W, 'actor_token_type': 'urn:ietf:params:oauth:token-type:access_token'}

print('\n######## the product request (actor_token + audience + scope)')
st, body, p = exchange('A. with actor_token', U, audience='tool-cases', scope='cases:read', **ACT)
if p:
    print('   sub == alice sub ?', p['sub'] == UP['sub'], '| tenant', p.get('tenant'), '| sub_kind', p.get('sub_kind'),
          '| has act ?', 'act' in p, '| aud exact ?', p['aud'] == 'tool-cases' or p['aud'] == ['tool-cases'],
          '| scope', p.get('scope'))

print('\n######## is actor_token looked at at all?')
exchange('B. without actor_token', U, audience='tool-cases', scope='cases:read')
exchange('L. actor_token = garbage', U, audience='tool-cases', scope='cases:read', actor_token='not.a.token',
         actor_token_type='urn:ietf:params:oauth:token-type:access_token')
exchange('M. actor_token = the user\'s own token (wrong actor)', U, audience='tool-cases', scope='cases:read',
         actor_token=U, actor_token_type='urn:ietf:params:oauth:token-type:access_token')
exchange('M2. actor_token with an unsupported actor_token_type', U, audience='tool-cases', scope='cases:read',
         actor_token=W, actor_token_type='urn:ietf:params:oauth:token-type:jwt')

print('\n######## refusals')
exchange('C. scope not held by alice (cases:delete)', U, audience='tool-cases', scope='cases:delete', **ACT)
exchange('D. unregistered audience', U, audience='not-a-client', scope='cases:read', **ACT)
st, body, p = exchange('N. two scopes narrowed to one', U, audience='tool-search', scope='cases:write', **ACT)
exchange('O. garbage subject_token', 'not.a.token', audience='tool-cases', scope='cases:read', **ACT)

print('\n######## lifetime and the subject token\'s own expiry')
Us = user_token('oa-api-short')
UsP = payload(Us)
st, body, p = exchange('K. exchange from a 20s user token', Us, audience='tool-cases', scope='cases:read', **ACT)
if p:
    print('   user exp-iat =', UsP['exp'] - UsP['iat'], '; exchanged exp-iat =', p['exp'] - p['iat'],
          '; exchanged outlives the user token by', p['exp'] - UsP['exp'], 's')
time.sleep(23)
exchange('J. exchange AFTER the user token expired', Us, audience='tool-cases', scope='cases:read', **ACT)

print('\n######## logout/revocation of the user session')
U2 = user_token()
s2, tokens = http('POST', TOKEN, {'grant_type': 'password', 'client_id': 'oa-api', 'username': 'alice',
                                  'password': 'alice-pw', 'scope': 'cases:read'}, form=True)
http('POST', f'{BASE}/realms/{REALM}/protocol/openid-connect/logout',
     {'client_id': 'oa-api', 'refresh_token': tokens.get('refresh_token', '')}, form=True)
exchange('R. exchange after the user session was logged out', tokens['access_token'], audience='tool-cases',
         scope='cases:read', **ACT)
print('\nDONE')
