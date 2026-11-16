package de.agiledev.agent.diagnostics;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import io.reactivex.rxjava3.core.Flowable;

import java.util.function.Supplier;

/** Attaches server identity to lazy MCP initialization and tool-list errors. */
public final class DiagnosticMcpToolset extends McpToolset {
    private final String server;
    private final String url;
    private final String[] secrets;

    public DiagnosticMcpToolset(StreamableHttpServerParameters parameters,
                                String server, String url, String... secrets) {
        super(parameters);
        this.server = server;
        this.url = url;
        this.secrets = secrets.clone();
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext context) {
        return diagnose(() -> super.getTools(context), server, url, secrets);
    }

    static Flowable<BaseTool> diagnose(Supplier<Flowable<BaseTool>> loading,
                                       String server, String url, String... secrets) {
        return Flowable.defer(loading::get).onErrorResumeNext(failure ->
                Flowable.error(new McpToolLoadingFailure(server, url, failure, secrets)));
    }
}
