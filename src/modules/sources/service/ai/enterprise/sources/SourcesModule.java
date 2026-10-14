package ai.enterprise.sources;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;

public final class SourcesModule implements Api.ModuleFactory {
    public String id() { return "sources"; }
    public void register(Api.Context context) {
        if (context.find(Api.DocumentAccessServiceV1.class).isEmpty()) {
            context.diagnostic("Открытие источников отключено: отсутствует DocumentAccessServiceV1.");
            return;
        }
        var service = new SourcesService(context);
        context.register(Api.SourceViewerServiceV1.class, service);
        context.command("open", args -> {
            if (args.isEmpty()) throw new Api.ServiceException("ARGUMENT_REQUIRED", "Не указан идентификатор документа.");
            return Api.await(service.openDocument(args.getFirst(), new Api.CancellationToken()));
        });
        context.command("open-source", args -> {
            if (args.isEmpty()) throw new Api.ServiceException("ARGUMENT_REQUIRED", "Не указан JSON привязки.");
            return Api.await(service.openSource(new Api.SourceRef(Json.object(args.getFirst())), new Api.CancellationToken()));
        });
    }
}
