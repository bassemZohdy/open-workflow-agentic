package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;

/** Test-only explicit graph composition. */
public final class ResearchAgentTestFactory {
    private ResearchAgentTestFactory() { }
    public static ResearchAgent create(DataSource data, GovernedLlmClient client, ApprovedModelContext context) throws Exception {
        var agent = new ResearchAgent();
        agent.checkpointSchema = "public";
        agent.dataSource = data;
        agent.llm = client;
        agent.approvedContext = context;
        agent.nodeBudget = new JdbcNodeBudget(data);
        agent.audit = event -> { };
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), ignored -> false);
        agent.build();
        return agent;
    }
}
