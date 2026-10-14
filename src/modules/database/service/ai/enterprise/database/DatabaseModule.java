package ai.enterprise.database;

import ai.enterprise.contracts.Api;

public final class DatabaseModule implements Api.ModuleFactory {
    public String id() { return "database"; }
    public void register(Api.Context context) {
        var database = new ReadOnlyDatabase(context);
        context.register(Api.DatabaseServiceV1.class, database);
        context.command("database-info", args -> database.query(
                "SELECT key,value FROM metadata ORDER BY key", java.util.List.of(), new Api.CancellationToken()));
    }
}
