package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.shell.PanelFactory;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public final class AssistantPanel implements PanelFactory {
    public String id() { return "assistant"; }
    public String title() { return "AI-помощник"; }
    public String slot() { return "left"; }

    public Composite create(Composite parent, Api.Context context) {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false); layout.marginWidth = 14; layout.marginHeight = 12;
        panel.setLayout(layout);
        Label heading = new Label(panel, SWT.NONE); heading.setText("AI-помощник по документам");
        Label info = new Label(panel, SWT.WRAP);
        info.setText("История хранится до закрытия приложения. Локальная модель проверяется при отправке.");
        info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Text transcript = new Text(panel, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.setText("Введите вопрос по документам текущего набора.");

        Group sourcesGroup = new Group(panel, SWT.NONE); sourcesGroup.setText("Источники ответа");
        sourcesGroup.setLayout(new FillLayout());
        GridData sourceSize = new GridData(SWT.FILL, SWT.FILL, true, false); sourceSize.heightHint = 130;
        sourcesGroup.setLayoutData(sourceSize);
        ScrolledComposite scroller = new ScrolledComposite(sourcesGroup, SWT.V_SCROLL);
        scroller.setExpandHorizontal(true); scroller.setExpandVertical(true);
        Composite sourceContent = new Composite(scroller, SWT.NONE); sourceContent.setLayout(new GridLayout(1, false));
        scroller.setContent(sourceContent);
        Label noSources = new Label(sourceContent, SWT.WRAP); noSources.setText("Ссылки и точные места появятся после поиска.");

        Text query = new Text(panel, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        query.setMessage("Введите запрос… Ctrl+Enter — отправить"); query.setTextLimit(131000);
        GridData querySize = new GridData(SWT.FILL, SWT.FILL, true, false); querySize.heightHint = 75; query.setLayoutData(querySize);
        Composite controls = new Composite(panel, SWT.NONE); controls.setLayout(new GridLayout(3, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button send = new Button(controls, SWT.PUSH); send.setText("Отправить");
        Button cancel = new Button(controls, SWT.PUSH); cancel.setText("Отмена"); cancel.setEnabled(false);
        Label status = new Label(controls, SWT.WRAP); status.setText("Готово"); status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var session = context.find(Api.AssistantSessionServiceV1.class);
        if (session.isEmpty()) { status.setText("Сервис помощника недоступен"); send.setEnabled(false); }
        Display display = panel.getDisplay();
        final Api.CancellationToken[] token = {null};
        StringBuilder draft = new StringBuilder();
        AtomicBoolean updatePending = new AtomicBoolean();
        Runnable redraw = () -> {
            if (panel.isDisposed()) return;
            StringBuilder text = new StringBuilder();
            if (session.isPresent()) for (var message : session.get().history()) {
                text.append(message.role().equals("user") ? "Вы" : "Помощник");
                if (message.state().equals("error")) text.append(" — сообщение");
                text.append(":\n").append(message.text()).append("\n\n");
            }
            synchronized (draft) { if (!draft.isEmpty()) text.append("Помощник — ответ проверяется:\n").append(draft); }
            transcript.setText(text.isEmpty() ? "Введите вопрос по документам текущего набора." : text.toString());
            transcript.setSelection(transcript.getCharCount());
        };
        Runnable queueRedraw = () -> {
            if (!display.isDisposed() && updatePending.compareAndSet(false, true)) display.asyncExec(() -> {
                if (panel.isDisposed()) { updatePending.set(false); return; }
                display.timerExec(40, () -> { updatePending.set(false); if (!panel.isDisposed()) redraw.run(); });
            });
        };
        java.util.function.Consumer<List<Api.Fragment>> showSources = fragments -> {
            for (Control control : sourceContent.getChildren()) control.dispose();
            if (fragments.isEmpty()) { Label label = new Label(sourceContent, SWT.NONE); label.setText("Исходные фрагменты не найдены."); }
            int index = 0;
            for (Api.Fragment fragment : fragments) {
                int number = ++index;
                Link link = new Link(sourceContent, SWT.WRAP);
                link.setText("<a>" + escape(number + ". " + fragment.title()) + "</a>");
                link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                Label locator = new Label(sourceContent, SWT.WRAP);
                String locatorText = fragment.sourceRefs().stream().map(AssistantPanel::locator).distinct().reduce((a, b) -> a + "; " + b).orElse("Место не указано");
                locator.setText("ID: " + fragment.fragmentId() + "\n" + locatorText);
                locator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                String warnings = String.join("; ", AssistantModule.Session.sourceWarnings(List.of(fragment)));
                if (!warnings.isBlank()) {
                    Label warning = new Label(sourceContent, SWT.WRAP); warning.setText("Ограничения: " + warnings);
                    warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                }
                link.addListener(SWT.Selection, event -> showSourceCard(panel, context, fragment, status));
            }
            sourceContent.layout(true, true);
            scroller.setMinSize(sourceContent.computeSize(Math.max(200, scroller.getClientArea().width), SWT.DEFAULT));
        };
        scroller.addListener(SWT.Resize, event -> scroller.setMinSize(sourceContent.computeSize(Math.max(200, scroller.getClientArea().width), SWT.DEFAULT)));

        Runnable submit = () -> {
            if (session.isEmpty() || !send.isEnabled() || query.getText().isBlank()) return;
            String question = query.getText();
            query.setText(""); send.setEnabled(false); cancel.setEnabled(true); status.setText("Обработка…");
            token[0] = new Api.CancellationToken();
            synchronized (draft) { draft.setLength(0); }
            Api.CancellationToken requestToken = token[0];
            session.get().send(question, event -> {
                if (event.type().equals("token")) { synchronized (draft) { draft.append(event.text()); } queueRedraw.run(); return; }
                if (event.type().equals("done") || event.type().equals("error")) synchronized (draft) { draft.setLength(0); }
                if (!display.isDisposed()) display.asyncExec(() -> {
                    if (panel.isDisposed() || token[0] != requestToken) return;
                    switch (event.type()) {
                        case "sources" -> showSources.accept(event.sources());
                        case "state" -> status.setText(event.text());
                        case "error" -> { status.setText(event.text()); showSources.accept(event.sources()); }
                        case "done" -> { status.setText("Ответ готов; ссылки проверены"); showSources.accept(event.sources()); }
                        default -> { }
                    }
                    controls.layout(); redraw.run();
                });
            }, requestToken).whenComplete((result, error) -> {
                if (!display.isDisposed()) display.asyncExec(() -> {
                    if (panel.isDisposed() || token[0] != requestToken) return;
                    send.setEnabled(true); cancel.setEnabled(false); token[0] = null;
                    synchronized (draft) { draft.setLength(0); }
                    if (error != null) status.setText(Objects.toString(error.getMessage(), "Ошибка обработки"));
                    else if (!result.success()) status.setText(result.error().message());
                    controls.layout(); redraw.run(); query.setFocus();
                });
            });
        };
        send.addListener(SWT.Selection, event -> submit.run());
        query.addListener(SWT.KeyDown, event -> { if (event.keyCode == SWT.CR && (event.stateMask & SWT.CTRL) != 0) { event.doit = false; submit.run(); } });
        cancel.addListener(SWT.Selection, event -> {
            if (token[0] != null) { status.setText("Отмена…"); cancel.setEnabled(false); token[0].cancel(); }
        });
        panel.addListener(SWT.Dispose, event -> { if (token[0] != null) token[0].cancel(); synchronized (draft) { draft.setLength(0); } });
        redraw.run();
        return panel;
    }

    private static void showSourceCard(Composite parent, Api.Context context, Api.Fragment fragment, Label parentStatus) {
        Shell card = new Shell(parent.getShell(), SWT.DIALOG_TRIM | SWT.RESIZE);
        Display cardDisplay = card.getDisplay();
        card.setText("Источник: " + fragment.title()); card.setLayout(new GridLayout(1, false));
        Text details = new Text(card, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        details.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        details.setText("Фрагмент: " + fragment.fragmentId() + "\nДокумент: " + fragment.documentId()
            + "\n\nИзвлечённое содержимое JSON (не отображение оригинала):\n" + fragment.text()
            + "\n\nТочные привязки:\n" + Json.stringify(fragment.sourceRefs())
            + "\n\nspan:\n" + Json.stringify(fragment.fields().get("span"))
            + "\n\nКачество и предупреждения:\n" + Json.stringify(fragment.fields().get("quality"))
            + "\n" + Json.stringify(fragment.fields().get("warnings")));
        Label notice = new Label(card, SWT.WRAP);
        notice.setText("Windows предложит программу для оригинала. Внешняя программа может создавать записи. Точное место остаётся в карточке; переход к нему не гарантируется.");
        notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button open = new Button(card, SWT.PUSH); open.setText("Открыть оригинал через Windows…");
        Label status = new Label(card, SWT.WRAP); status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var viewer = context.find(Api.SourceViewerServiceV1.class);
        open.setEnabled(viewer.isPresent());
        if (viewer.isEmpty()) status.setText("Просмотр источников отсутствует в этой сборке");
        Api.CancellationToken token = new Api.CancellationToken();
        open.addListener(SWT.Selection, event -> {
            open.setEnabled(false); status.setText("Проверка пути и SHA-256…");
            var stage = fragment.sourceRefs().isEmpty() ? viewer.get().openDocument(fragment.documentId(), token)
                : viewer.get().openSource(fragment.sourceRefs().getFirst(), token);
            stage.whenComplete((result, error) -> {
                if (cardDisplay.isDisposed()) return;
                cardDisplay.asyncExec(() -> {
                    if (card.isDisposed()) return;
                    open.setEnabled(true); String message = error != null ? Objects.toString(error.getMessage(), "Ошибка открытия")
                        : result.success() ? "Оригинал передан Windows. Точное место указано выше." : result.error().message();
                    status.setText(message); if (!parentStatus.isDisposed()) parentStatus.setText(message); card.layout();
                });
            });
        });
        card.addListener(SWT.Dispose, event -> token.cancel());
        card.setSize(640, 540); card.open();
    }
    private static String locator(Api.SourceRef ref) {
        Map<String,Object> f = ref.fields();
        return switch (ref.kind()) {
            case "pdf_region" -> "страница " + f.get("page") + ", область " + f.get("bbox");
            case "sheet_range" -> "лист " + f.get("sheet") + ", диапазон " + f.get("range");
            case "ooxml_node", "drawing" -> "часть " + f.get("part") + ", XPath " + f.get("xpath") + " (для производного формата — в производном файле)";
            default -> "артефакт " + ref.artifactId();
        };
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
