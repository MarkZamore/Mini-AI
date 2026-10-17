package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.shell.PanelFactory;
import ai.enterprise.shell.SoftButton;
import ai.enterprise.shell.UiHost;
import ai.enterprise.shell.UiTheme;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public final class AssistantPanel implements PanelFactory {
    private static final AtomicLong UI_OPERATION_IDS = new AtomicLong();
    public String id() { return "assistant"; }
    public String title() { return UiTheme.NAME; }
    public String slot() { return "left"; }

    public Composite create(Composite parent, Api.Context context) {
        UiHost host = UiHost.find(parent); UiTheme theme = host.theme();
        Composite panel = new Composite(parent, SWT.NONE); theme.apply(panel);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16; layout.marginHeight = 16; layout.verticalSpacing = 12; panel.setLayout(layout);
        Composite historyArea = new Composite(panel, SWT.NONE); theme.apply(historyArea);
        historyArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        StackLayout historyStack = new StackLayout(); historyArea.setLayout(historyStack);
        Canvas empty = new Canvas(historyArea, SWT.DOUBLE_BUFFERED); theme.apply(empty);
        empty.setData("enterpriseai.ui.empty", Boolean.TRUE);
        empty.addPaintListener(event -> {
            event.gc.setFont(theme.brandFont()); event.gc.setForeground(theme.accent());
            Point size = event.gc.textExtent(UiTheme.NAME); var bounds = empty.getClientArea();
            event.gc.drawText(UiTheme.NAME, Math.max(0, (bounds.width - size.x) / 2),
                Math.max(0, (bounds.height - size.y) / 2), true);
        });
        Text transcript = new Text(historyArea, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        theme.apply(transcript); transcript.setData("enterpriseai.ui.transcript", Boolean.TRUE);
        historyStack.topControl = empty;

        RoundedSourcesBlock sourcesGroup = new RoundedSourcesBlock(panel, theme, "Источники ответа");
        sourcesGroup.content().setLayout(new FillLayout());
        GridData sourceSize = new GridData(SWT.FILL, SWT.FILL, true, false);
        sourceSize.heightHint = 130;
        sourcesGroup.setLayoutData(sourceSize);
        ScrolledComposite scroller = new ScrolledComposite(sourcesGroup.content(), SWT.V_SCROLL); theme.apply(scroller);
        scroller.setExpandHorizontal(true); scroller.setExpandVertical(true);
        Composite sourceContent = new Composite(scroller, SWT.NONE); theme.apply(sourceContent);
        sourceContent.setLayout(new GridLayout(1, false)); scroller.setContent(sourceContent);

        RequestActivity activity = new RequestActivity(panel, theme);
        Composite composer = new Composite(panel, SWT.DOUBLE_BUFFERED); theme.apply(composer);
        composer.setBackground(theme.field()); composer.setData("enterpriseai.ui.composer", Boolean.TRUE);
        GridLayout composerLayout = new GridLayout(1, false);
        composerLayout.marginWidth = 14; composerLayout.marginHeight = 12;
        composerLayout.verticalSpacing = 6; composer.setLayout(composerLayout);
        composer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        composer.addPaintListener(event -> {
            var area = composer.getClientArea(); event.gc.setAdvanced(false);
            event.gc.setBackground(theme.panel()); event.gc.fillRectangle(area);
            event.gc.setBackground(theme.field());
            event.gc.fillRoundRectangle(0, 0, Math.max(0, area.width - 1), Math.max(0, area.height - 1), 26, 26);
            event.gc.setForeground(theme.border());
            event.gc.drawRoundRectangle(0, 0, Math.max(0, area.width - 1), Math.max(0, area.height - 1), 26, 26);
        });
        Text query = new Text(composer, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL); theme.apply(query);
        query.setBackground(theme.field()); query.setData("enterpriseai.ui.query", Boolean.TRUE);
        query.setMessage("Напишите сообщение"); query.setTextLimit(131000);
        GridData querySize = new GridData(SWT.FILL, SWT.FILL, true, false);
        querySize.heightHint = 88; querySize.minimumHeight = 56; query.setLayoutData(querySize);
        Composite actions = new Composite(composer, SWT.NONE); theme.apply(actions); actions.setBackground(theme.field());
        GridLayout actionLayout = new GridLayout(2, false);
        actionLayout.marginWidth = 0; actionLayout.marginHeight = 0; actionLayout.horizontalSpacing = 8;
        actions.setLayout(actionLayout); actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Composite spacer = new Composite(actions, SWT.NONE); spacer.setBackground(theme.field());
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        SoftButton send = new SoftButton(actions, theme, "Отправить", true);
        send.setData("enterpriseai.ui.send", Boolean.TRUE); send.setEnabled(false); send.setToolTipText("Отправить сообщение");
        GridData sendSize = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        sendSize.widthHint = 40; sendSize.heightHint = 40; send.setLayoutData(sendSize);
        composer.setTabList(new Control[] {query, actions}); actions.setTabList(new Control[] {send});

        var session = context.find(Api.AssistantSessionServiceV1.class); Display display = panel.getDisplay();
        final Request[] active = {null};
        AtomicBoolean updatePending = new AtomicBoolean();
        Runnable updateActions = () -> {
            if (panel.isDisposed()) return;
            Request request = active[0];
            send.setStopMode(request != null);
            send.setEnabled(request != null ? !request.token.cancelled()
                : session.isPresent() && !query.getText().isBlank());
        };
        Runnable redraw = () -> {
            if (panel.isDisposed()) return;
            StringBuilder text = new StringBuilder();
            if (session.isPresent()) for (var message : session.get().history()) {
                text.append(message.role().equals("user") ? "Вы" : "Помощник");
                if (message.state().equals("error")) text.append(" — сообщение");
                text.append(":\n").append(message.text()).append("\n\n");
            }
            Request current = active[0];
            if (current != null) synchronized (current.draft) {
                if (!current.draft.isEmpty()) text.append("Помощник — предварительный ответ:\n").append(current.draft);
            }
            transcript.setText(text.toString()); transcript.setSelection(transcript.getCharCount());
            Control visible = text.isEmpty() ? empty : transcript;
            if (historyStack.topControl != visible) { historyStack.topControl = visible; historyArea.layout(); }
        };
        Runnable queueRedraw = () -> {
            if (!display.isDisposed() && updatePending.compareAndSet(false, true)) display.asyncExec(() -> {
                if (panel.isDisposed()) { updatePending.set(false); return; }
                display.timerExec(40, () -> { updatePending.set(false); if (!panel.isDisposed()) redraw.run(); });
            });
        };
        java.util.function.Consumer<List<Api.Fragment>> showSources = fragments -> {
            for (Control control : sourceContent.getChildren()) control.dispose();
            int index = 0;
            for (Api.Fragment fragment : fragments) {
                int number = ++index; Link link = new Link(sourceContent, SWT.WRAP); theme.apply(link);
                link.setText("<a>" + escape(number + ". " + fragment.title()) + "</a>");
                link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                Label locator = new Label(sourceContent, SWT.WRAP); theme.apply(locator);
                String locatorText = fragment.sourceRefs().stream().map(AssistantPanel::locator).distinct()
                    .reduce((a, b) -> a + "; " + b).orElse("Место не указано");
                locator.setText("ID: " + fragment.fragmentId() + "\n" + locatorText);
                locator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                String warnings = String.join("; ", AssistantModule.Session.sourceWarnings(List.of(fragment)));
                if (!warnings.isBlank()) {
                    Label warning = new Label(sourceContent, SWT.WRAP); theme.apply(warning);
                    warning.setText("Ограничения: " + warnings);
                    warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                }
                link.addListener(SWT.Selection, event -> showSourceCard(panel, context, fragment));
            }
            sourceContent.layout(true, true);
            scroller.setMinSize(sourceContent.computeSize(Math.max(200, scroller.getClientArea().width), SWT.DEFAULT));
            panel.layout(true, true);
        };
        scroller.addListener(SWT.Resize, event -> scroller.setMinSize(
            sourceContent.computeSize(Math.max(200, scroller.getClientArea().width), SWT.DEFAULT)));
        java.util.function.BiConsumer<Request, Api.ServiceError> reportError = (request, error) -> {
            if (error != null && !request.token.cancelled() && !"CANCELLED".equals(error.code())
                && request.errorShown.compareAndSet(false, true)) host.error(request.id, error);
        };
        Runnable submit = () -> {
            if (session.isEmpty() || active[0] != null || query.getText().isBlank()) return;
            String question = query.getText(); Request request = new Request("assistant-request-" + UI_OPERATION_IDS.incrementAndGet());
            active[0] = request; query.setText(""); updateActions.run();
            activity.start(request);
            showSources.accept(List.of());
            try {
                session.get().send(question, event -> {
                    boolean terminal = event.type().equals("done") || event.type().equals("error");
                    if (terminal ? !request.finished.compareAndSet(false, true) : request.finished.get()) return;
                    if (event.type().equals("token")) {
                        if (!request.token.cancelled()) synchronized (request.draft) { request.draft.append(event.text()); }
                        queueRedraw.run(); return;
                    }
                    if (event.type().equals("done") || event.type().equals("error"))
                        synchronized (request.draft) { request.draft.setLength(0); }
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (panel.isDisposed() || active[0] != request) return;
                        switch (event.type()) {
                            case "state" -> {
                                if (!request.token.cancelled()) activity.stage(request, event.text());
                            }
                            case "sources" -> showSources.accept(event.sources());
                            case "done" -> { activity.stop(request); showSources.accept(event.sources()); }
                            case "error" -> {
                                activity.stop(request);
                                showSources.accept(event.sources());
                                reportError.accept(request, event.error() == null
                                    ? new Api.ServiceError("FAILED", event.text()) : event.error());
                            }
                            default -> { }
                        }
                        redraw.run();
                    });
                }, request.token).whenComplete((result, error) -> {
                    if (!display.isDisposed()) display.asyncExec(() -> {
                        if (panel.isDisposed() || active[0] != request) return;
                        request.finished.set(true); activity.stop(request);
                        if (error != null) reportError.accept(request, Api.failure(error).error());
                        else if (result == null) reportError.accept(request,
                            new Api.ServiceError("FAILED", "Помощник не вернул результат обработки"));
                        else if (!result.success()) reportError.accept(request, result.error());
                        active[0] = null; synchronized (request.draft) { request.draft.setLength(0); }
                        updateActions.run(); redraw.run(); query.setFocus();
                    });
                });
            } catch (Throwable error) {
                request.finished.set(true); activity.stop(request);
                active[0] = null; reportError.accept(request, Api.failure(error).error());
                synchronized (request.draft) { request.draft.setLength(0); } updateActions.run(); redraw.run(); query.setFocus();
            }
            redraw.run();
        };
        send.addListener(SWT.Selection, event -> {
            Request request = active[0];
            if (request != null) {
                if (!request.token.cancelled()) { request.token.cancel(); activity.stage(request, "Отмена…"); updateActions.run(); }
            } else submit.run();
        });
        query.addListener(SWT.Modify, event -> updateActions.run());
        query.addListener(SWT.KeyDown, event -> {
            if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && (event.stateMask & SWT.SHIFT) == 0) {
                event.doit = false; submit.run();
            }
        });
        panel.addListener(SWT.Dispose, event -> {
            if (active[0] != null) {
                active[0].token.cancel(); synchronized (active[0].draft) { active[0].draft.setLength(0); }
            }
        });
        updateActions.run(); redraw.run(); return panel;
    }

    private static void showSourceCard(Composite parent, Api.Context context, Api.Fragment fragment) {
        UiHost host = UiHost.find(parent); UiTheme theme = host.theme();
        Shell card = new Shell(parent.getShell(), SWT.DIALOG_TRIM | SWT.RESIZE); theme.decorate(card);
        Display cardDisplay = card.getDisplay(); card.setText("Источник: " + fragment.title());
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16; layout.marginHeight = 16; layout.verticalSpacing = 12; card.setLayout(layout);
        Text details = new Text(card, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL); theme.apply(details);
        details.setBackground(theme.field()); details.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        details.setText("Фрагмент: " + fragment.fragmentId() + "\nДокумент: " + fragment.documentId()
            + "\n\nИзвлечённое содержимое JSON (не отображение оригинала):\n" + fragment.text()
            + "\n\nТочные привязки:\n" + Json.stringify(fragment.sourceRefs())
            + "\n\nspan:\n" + Json.stringify(fragment.fields().get("span"))
            + "\n\nКачество и предупреждения:\n" + Json.stringify(fragment.fields().get("quality"))
            + "\n" + Json.stringify(fragment.fields().get("warnings")));
        Label notice = new Label(card, SWT.WRAP); theme.apply(notice);
        notice.setText("Windows предложит программу для оригинала. Внешняя программа может создавать записи. Точное место остаётся в карточке; переход к нему не гарантируется.");
        notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        SoftButton open = new SoftButton(card, theme, "Открыть оригинал через Windows…", false);
        var viewer = context.find(Api.SourceViewerServiceV1.class); open.setEnabled(viewer.isPresent());
        if (viewer.isEmpty()) {
            Label unavailable = new Label(card, SWT.WRAP); theme.apply(unavailable);
            unavailable.setText("Просмотр источников отсутствует в этой сборке");
            unavailable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }
        final Api.CancellationToken[] token = {null};
        open.addListener(SWT.Selection, event -> {
            if (token[0] != null || viewer.isEmpty()) return;
            open.setEnabled(false); Api.CancellationToken requestToken = new Api.CancellationToken(); token[0] = requestToken;
            String operationId = "source-card-" + UI_OPERATION_IDS.incrementAndGet();
            try {
                var stage = fragment.sourceRefs().isEmpty() ? viewer.get().openDocument(fragment.documentId(), requestToken)
                    : viewer.get().openSource(fragment.sourceRefs().getFirst(), requestToken);
                stage.whenComplete((result, error) -> {
                    if (cardDisplay.isDisposed()) return;
                    cardDisplay.asyncExec(() -> {
                        if (card.isDisposed() || token[0] != requestToken) return;
                        token[0] = null; open.setEnabled(true);
                        Api.ServiceError failure = error != null ? Api.failure(error).error()
                            : result == null ? new Api.ServiceError("FAILED", "Просмотрщик не вернул результат открытия") : result.error();
                        if (failure != null && !requestToken.cancelled() && !"CANCELLED".equals(failure.code())) host.error(operationId, failure);
                    });
                });
            } catch (Throwable error) {
                token[0] = null; open.setEnabled(true); host.error(operationId, Api.failure(error).error());
            }
        });
        card.addListener(SWT.Dispose, event -> { if (token[0] != null) token[0].cancel(); });
        card.setSize(640, 540); card.open();
    }

    private static final class Request {
        final String id; final Api.CancellationToken token = new Api.CancellationToken();
        final AtomicBoolean errorShown = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final StringBuilder draft = new StringBuilder(); Request(String id) { this.id = id; }
    }

    /** One UI-thread timer belongs to the current request and never survives its row. */
    private static final class RequestActivity {
        final Composite row;
        final Canvas spinner;
        final Label stage, elapsed;
        final Display display;
        final Runnable tick;
        Request owner;
        long started, seconds = -1;
        int frame;

        RequestActivity(Composite parent, UiTheme theme) {
            display = parent.getDisplay();
            row = new Composite(parent, SWT.NONE); theme.apply(row);
            row.setData("enterpriseai.ui.activity", Boolean.TRUE);
            GridLayout layout = new GridLayout(3, false);
            layout.marginWidth = 0; layout.marginHeight = 0; layout.horizontalSpacing = 8;
            row.setLayout(layout);
            GridData size = new GridData(SWT.FILL, SWT.CENTER, true, false);
            size.exclude = true; row.setLayoutData(size); row.setVisible(false);
            spinner = new Canvas(row, SWT.DOUBLE_BUFFERED); theme.apply(spinner);
            spinner.setData("enterpriseai.ui.activity-spinner", Boolean.TRUE);
            spinner.setLayoutData(new GridData(18, 18));
            spinner.addPaintListener(event -> {
                var bounds = spinner.getClientArea();
                for (int dot = 0; dot < 8; dot++) {
                    double angle = dot * Math.PI / 4;
                    event.gc.setBackground(dot == frame ? theme.accent() : theme.muted());
                    event.gc.fillOval((int)Math.round(bounds.width / 2.0 + 6 * Math.cos(angle)) - 2,
                        (int)Math.round(bounds.height / 2.0 + 6 * Math.sin(angle)) - 2, 3, 3);
                }
            });
            stage = new Label(row, SWT.WRAP); theme.apply(stage);
            stage.setData("enterpriseai.ui.activity-stage", Boolean.TRUE);
            GridData stageSize = new GridData(SWT.FILL, SWT.CENTER, true, false);
            stageSize.widthHint = 0; stage.setLayoutData(stageSize);
            elapsed = new Label(row, SWT.RIGHT); theme.apply(elapsed);
            elapsed.setData("enterpriseai.ui.activity-elapsed", Boolean.TRUE);
            elapsed.setText("· 00:00:00");
            GridData timeSize = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
            timeSize.widthHint = elapsed.computeSize(SWT.DEFAULT, SWT.DEFAULT).x; elapsed.setLayoutData(timeSize);
            tick = () -> {
                if (row.isDisposed() || owner == null) return;
                long value = Math.max(0, (System.nanoTime() - started) / 1_000_000_000L);
                if (seconds != value) {
                    seconds = value;
                    elapsed.setText("· " + (value < 3600
                        ? String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)
                        : String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)));
                }
                frame = (frame + 1) % 8; spinner.redraw();
                display.timerExec(100, tickTask());
            };
            row.addListener(SWT.Dispose, event -> { owner = null; display.timerExec(-1, tick); });
        }
        private Runnable tickTask() { return tick; }
        void start(Request request) {
            display.timerExec(-1, tick); owner = request; started = System.nanoTime(); seconds = -1; frame = 0;
            stage.setText("Обработка запроса…");
            ((GridData)row.getLayoutData()).exclude = false; row.setVisible(true);
            tick.run(); row.getParent().layout(true, true);
        }
        void stage(Request request, String text) {
            if (owner != request || row.isDisposed()) return;
            stage.setText(text); row.getParent().layout(true, true);
        }
        void stop(Request request) {
            if (owner != request || row.isDisposed()) return;
            owner = null; display.timerExec(-1, tick);
            row.setVisible(false); ((GridData)row.getLayoutData()).exclude = true;
            row.getParent().layout(true, true);
        }
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
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
