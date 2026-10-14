package ai.enterprise.documents;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;

/** Services register through stable contracts; no optional implementation is imported. */
public final class DocumentsModule implements Api.ModuleFactory {
    public String id() { return "documents"; }

    public void register(Api.Context context) {
        if (context.find(Api.DatabaseServiceV1.class).isEmpty()) {
            context.diagnostic("Документы отключены: отсутствует DatabaseServiceV1.");
            return;
        }
        var service = new DocumentsService(context);
        context.register(Api.DocumentAccessServiceV1.class, service);
        context.command("catalog", args -> Api.await(service.catalog(new Api.CancellationToken())));
        context.command("document", args -> Api.await(service.document(argument(args, 0), new Api.CancellationToken())));
        context.command("canonical", args -> Api.await(service.canonical(argument(args, 0), new Api.CancellationToken())));
        context.command("resolve", args -> Api.await(service.resolveSource(new Api.SourceRef(Json.object(argument(args, 0))), new Api.CancellationToken())));
    }

    private static String argument(java.util.List<String> args, int index) {
        if (args.size() <= index) throw new Api.ServiceException("ARGUMENT_REQUIRED", "Не указан идентификатор документа или JSON привязки.");
        return args.get(index);
    }
}
