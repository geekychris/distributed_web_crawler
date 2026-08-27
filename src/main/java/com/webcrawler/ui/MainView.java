package com.webcrawler.ui;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Command;
import com.webcrawler.model.CrawlJob;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
import com.webcrawler.model.FeedPack;
import com.webcrawler.service.ActivityFeed;
import com.webcrawler.service.CrawlJobService;
import com.webcrawler.service.CrawlerUIService;
import com.webcrawler.service.FeedPackService;
import com.webcrawler.service.FeedPoller;
import com.webcrawler.service.FeedRepository;
import com.webcrawler.service.ScopeService;
import com.webcrawler.service.StatsService;
import com.webcrawler.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Route("")
@PageTitle("Distributed Web Crawler Dashboard")
public class MainView extends VerticalLayout {

    private final CrawlerUIService crawlerService;
    private final StorageService storageService;
    private final ActivityFeed activityFeed;
    private final CrawlJobService jobService;
    private final com.webcrawler.queue.UrlQueue urlQueue;
    private final FeedRepository feedRepo;
    private final FeedPackService feedPacks;
    private final FeedPoller feedPoller;
    private final StatsService statsService;

    // Dashboard components
    private Button startStopButton;
    private Span statusSpan;
    private Span uptimeSpan;
    private Span pageCountSpan;
    private Div activityBox;
    
    // URL submission components
    private TextArea urlsTextArea;
    
    // Database query components
    private TextField searchField;
    private ComboBox<String> contentTypeCombo;
    private Grid<StorageService.PageMetadata> pageGrid;
    private Grid<FeedItem> feedItemGrid;
    private Span resultsCountSpan;
    
    // Auto-refresh
    private ScheduledExecutorService scheduler;
    
    @Autowired
    public MainView(CrawlerUIService crawlerService, StorageService storageService,
                    ActivityFeed activityFeed, CrawlJobService jobService,
                    com.webcrawler.queue.UrlQueue urlQueue,
                    FeedRepository feedRepo, FeedPackService feedPacks,
                    FeedPoller feedPoller, StatsService statsService) {
        this.crawlerService = crawlerService;
        this.storageService = storageService;
        this.activityFeed = activityFeed;
        this.jobService = jobService;
        this.urlQueue = urlQueue;
        this.feedRepo = feedRepo;
        this.feedPacks = feedPacks;
        this.feedPoller = feedPoller;
        this.statsService = statsService;
        
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        
        // Create header
        add(createHeader());
        
        Tabs tabs = new Tabs();
        Tab dashboardTab = new Tab("Dashboard");
        Tab startCrawlTab = new Tab("Start a Crawl");
        Tab jobsTab = new Tab("Jobs");
        Tab feedsTab = new Tab("Feeds");
        Tab packsTab = new Tab("Feed Packs");
        Tab queryTab = new Tab("Query Database");
        Tab statsTab = new Tab("Stats");
        tabs.add(dashboardTab, startCrawlTab, jobsTab, feedsTab, packsTab, queryTab, statsTab);

        Div contentArea = new Div();
        contentArea.setSizeFull();
        contentArea.add(createDashboardContent());

        tabs.addSelectedChangeListener(event -> {
            contentArea.removeAll();
            Tab selectedTab = event.getSelectedTab();
            if (selectedTab == dashboardTab) contentArea.add(createDashboardContent());
            else if (selectedTab == startCrawlTab) contentArea.add(createStartCrawlContent());
            else if (selectedTab == jobsTab) contentArea.add(createJobsContent());
            else if (selectedTab == feedsTab) contentArea.add(createFeedsContent());
            else if (selectedTab == packsTab) contentArea.add(createFeedPacksContent());
            else if (selectedTab == queryTab) contentArea.add(createQueryContent());
            else if (selectedTab == statsTab) contentArea.add(createStatsContent());
        });

        add(tabs, contentArea);
        
        // Initialize auto-refresh
        updateDashboard();
    }
    
    private Component createHeader() {
        H1 title = new H1("🕷️ Distributed Web Crawler Dashboard");
        title.getStyle().set("margin", "0 0 1rem 0");
        return title;
    }
    
    private Component createDashboardContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSpacing(true);
        layout.setPadding(false);

        // Control panel
        boolean running = false;
        try { running = crawlerService.getCrawlerStats().isRunning(); } catch (Exception ignored) {}
        startStopButton = new Button(running ? "Stop Crawler" : "Start Crawler");
        startStopButton.addThemeVariants(running ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS);
        startStopButton.addClickListener(e -> toggleCrawler());
        HorizontalLayout controls = new HorizontalLayout(startStopButton);
        controls.setAlignItems(FlexComponent.Alignment.CENTER);

        // Status display
        statusSpan = new Span();
        uptimeSpan = new Span();
        pageCountSpan = new Span();

        VerticalLayout statusLayout = new VerticalLayout(
            new H3("Status"),
            statusSpan,
            uptimeSpan,
            pageCountSpan,
            new Hr(),
            new H3("Controls"),
            controls
        );
        statusLayout.setPadding(true);
        statusLayout.getStyle()
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("border-radius", "var(--lumo-border-radius)");

        // Live activity feed — the last N crawl outcomes so users can see
        // what's happening after submitting a URL.
        activityBox = new Div();
        activityBox.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius)")
                .set("padding", "1rem")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("font-family", "monospace")
                .set("font-size", "12px")
                .set("white-space", "pre-wrap")
                .set("max-height", "300px")
                .set("overflow", "auto");
        VerticalLayout activityLayout = new VerticalLayout(new H3("Recent activity"), activityBox);
        activityLayout.setPadding(false);

        layout.add(statusLayout, activityLayout);

        // Populate immediately so we don't wait 5s for the next refresh tick.
        updateDashboard();
        updateActivity();

        return layout;
    }

    private void updateActivity() {
        if (activityBox == null) return;
        try {
            List<ActivityFeed.Event> events = activityFeed.recent(30);
            StringBuilder sb = new StringBuilder();
            if (events.isEmpty()) {
                sb.append("No activity yet. Submit a URL from the 'Start a Crawl' tab.");
            }
            for (ActivityFeed.Event ev : events) {
                String stamp = DateTimeFormatter.ofPattern("HH:mm:ss")
                        .format(ev.at().atZone(ZoneId.systemDefault()));
                String icon = switch (ev.kind()) {
                    case CRAWLED -> "✓";
                    case REJECTED -> "✗";
                    case ERROR -> "!";
                };
                sb.append(stamp).append(" ").append(icon).append(" ")
                        .append(ev.url()).append("  ").append(ev.detail()).append('\n');
            }
            activityBox.setText(sb.toString());
        } catch (Exception ignored) {}
    }
    
    private Component createStartCrawlContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSpacing(true);
        layout.setPadding(false);

        TextField nameField = new TextField("Name");
        nameField.setPlaceholder("My crawl");
        nameField.setWidthFull();

        urlsTextArea = new TextArea("Seed URLs (one per line)");
        urlsTextArea.setPlaceholder("https://amiga.com/\nhttps://example.com/");
        urlsTextArea.setWidthFull();
        urlsTextArea.setHeight("140px");

        RadioButtonGroup<String> scopeMode = new RadioButtonGroup<>();
        scopeMode.setLabel("Scope");
        scopeMode.setItems("Same host only", "Same domain (recommended)", "Any domain");
        scopeMode.setValue("Same domain (recommended)");
        scopeMode.setHelperText(
                "Same host: only pages on the exact host (news.example.com). "
              + "Same domain: news.example.com plus any *.example.com. "
              + "Any: follow links anywhere (respects excludes below).");

        IntegerField maxDepthField = intField("Max depth", 3, 0, 20);
        IntegerField maxPagesField = intField("Max pages (total, -1 = unlimited)", 100, -1, 1_000_000);
        IntegerField maxPagesPerDomainField = intField("Max pages per domain", 50, -1, 1_000_000);
        IntegerField maxDomainsField = intField("Max distinct domains", -1, -1, 100_000);

        HorizontalLayout budgets = new HorizontalLayout(
                maxDepthField, maxPagesField, maxPagesPerDomainField, maxDomainsField);
        budgets.setWidthFull();
        budgets.getChildren().forEach(c -> {
            if (c instanceof HasSize hs) hs.setWidth("22%");
        });

        TextArea includeArea = new TextArea("Additional allowed domain patterns (regex, one per line, optional)");
        includeArea.setPlaceholder(".*\\.wikipedia\\.org$");
        includeArea.setWidthFull();
        includeArea.setHeight("70px");

        TextArea excludeArea = new TextArea("Exclude URL patterns (regex, one per line, optional)");
        excludeArea.setPlaceholder("/logout.*\n\\.pdf$");
        excludeArea.setWidthFull();
        excludeArea.setHeight("70px");

        Button startButton = new Button("Start Crawl", new Icon(VaadinIcon.PLAY));
        startButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        startButton.addClickListener(e -> startCrawl(
                nameField.getValue(),
                urlsTextArea.getValue(),
                scopeMode.getValue(),
                maxDepthField.getValue(),
                maxPagesField.getValue(),
                maxPagesPerDomainField.getValue(),
                maxDomainsField.getValue(),
                includeArea.getValue(),
                excludeArea.getValue()));

        Paragraph help = new Paragraph(
                "This creates a Crawl Job with the settings below and starts it. "
              + "Track progress on the Jobs tab.");
        help.getStyle().set("color", "var(--lumo-secondary-text-color)").set("margin", "0");

        layout.add(new H3("Start a Crawl"), help, nameField, urlsTextArea, scopeMode,
                new H3("Budgets"), budgets,
                new H3("Advanced (optional)"), includeArea, excludeArea,
                startButton);
        return layout;
    }

    private IntegerField intField(String label, int def, int min, int max) {
        IntegerField f = new IntegerField(label);
        f.setValue(def);
        f.setMin(min);
        f.setMax(max);
        f.setStepButtonsVisible(true);
        return f;
    }

    private void startCrawl(String name, String urlsText, String scopeChoice,
                            Integer maxDepth, Integer maxPages, Integer maxPagesPerDomain,
                            Integer maxDomains, String includes, String excludes) {
        try {
            Set<String> seeds = parseLines(urlsText).stream()
                    .map(this::coerceScheme)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (seeds.isEmpty()) {
                showNotification("Enter at least one seed URL", true);
                return;
            }
            String jobName = (name == null || name.isBlank())
                    ? "crawl-" + DateTimeFormatter.ofPattern("HH:mm:ss")
                            .format(Instant.now().atZone(ZoneId.systemDefault()))
                    : name.trim();

            Set<String> allowed = new java.util.LinkedHashSet<>(parseLines(includes));
            ScopeService.Mode mode;
            switch (scopeChoice) {
                case "Same host only" -> {
                    mode = ScopeService.Mode.HOST;
                    seeds.forEach(s -> allowed.add("^" + java.util.regex.Pattern.quote(hostOf(s)) + "$"));
                }
                case "Any domain" -> mode = ScopeService.Mode.ANY;
                default -> {
                    mode = ScopeService.Mode.DOMAIN;
                    seeds.forEach(s -> {
                        String d = registrableDomain(hostOf(s));
                        if (d != null) allowed.add("(^|\\.)" + java.util.regex.Pattern.quote(d) + "$");
                    });
                }
            }
            seeds.forEach(s -> crawlerService.trustSubmissionFor(s, mode));

            CrawlJob job = jobService.create(
                    jobName, seeds, allowed, new java.util.LinkedHashSet<>(parseLines(excludes)),
                    maxDepth == null ? -1 : maxDepth,
                    maxPages == null ? -1 : maxPages,
                    maxPagesPerDomain == null ? -1 : maxPagesPerDomain,
                    maxDomains == null ? -1 : maxDomains);

            jobService.updateStatus(job.jobId(), CrawlJob.Status.RUNNING);
            List<java.util.concurrent.CompletableFuture<Void>> enqueues = new java.util.ArrayList<>();
            for (String seed : seeds) {
                enqueues.add(urlQueue.enqueue(new com.webcrawler.model.CrawlRequest(
                        seed, 0, null, Instant.now(), 1, 0, null, job.jobId())));
            }
            java.util.concurrent.CompletableFuture.allOf(
                            enqueues.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .whenComplete((v, err) -> getUI().ifPresent(ui -> ui.access(() -> {
                        if (err != null) {
                            showNotification("Some seeds failed to enqueue: " + err.getMessage(), true);
                        } else {
                            showNotification("Started job '" + jobName + "' with " + seeds.size()
                                    + " seed(s) — see Jobs tab", false);
                        }
                    })));
        } catch (Exception ex) {
            showNotification("Failed to start crawl: " + ex.getMessage(), true);
        }
    }

    private List<String> parseLines(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String coerceScheme(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return "https://" + url;
    }

    private String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return ""; }
    }

    private String registrableDomain(String host) {
        if (host == null) return null;
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private Component createJobsContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        Grid<CrawlJob> grid = new Grid<>(CrawlJob.class, false);
        grid.addColumn(CrawlJob::name).setHeader("Name").setFlexGrow(1);
        grid.addColumn(j -> j.status().name()).setHeader("Status").setWidth("110px");
        grid.addColumn(j -> j.seedUrls().size()).setHeader("Seeds").setWidth("80px");
        grid.addColumn(j -> renderBudget(j.maxPages())).setHeader("Max pages").setWidth("100px");
        grid.addColumn(j -> renderBudget(j.maxPagesPerDomain())).setHeader("Per domain").setWidth("100px");
        grid.addColumn(new ComponentRenderer<>(job -> {
            long total = 0, distinct = 0;
            try {
                total = jobService.totalCrawled(job.jobId());
                distinct = jobService.distinctDomains(job.jobId());
            } catch (Exception ignored) {}
            String label = total + " page(s) / " + distinct + " domain(s)";
            if (job.maxPages() > 0) {
                ProgressBar pb = new ProgressBar(0, job.maxPages(),
                        Math.min(job.maxPages(), total));
                VerticalLayout box = new VerticalLayout(new Span(label), pb);
                box.setPadding(false); box.setSpacing(false);
                return box;
            }
            return new Span(label);
        })).setHeader("Progress").setFlexGrow(2);
        grid.addColumn(new ComponentRenderer<>(job -> {
            HorizontalLayout btns = new HorizontalLayout();
            Button pause = new Button("Pause", e -> {
                jobService.updateStatus(job.jobId(), CrawlJob.Status.PAUSED);
                refreshJobsGrid(grid);
            });
            Button resume = new Button("Resume", e -> {
                jobService.updateStatus(job.jobId(), CrawlJob.Status.RUNNING);
                refreshJobsGrid(grid);
            });
            Button cancel = new Button("Cancel", e -> {
                jobService.updateStatus(job.jobId(), CrawlJob.Status.CANCELLED);
                refreshJobsGrid(grid);
            });
            cancel.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            pause.addThemeVariants(ButtonVariant.LUMO_SMALL);
            resume.addThemeVariants(ButtonVariant.LUMO_SMALL);
            btns.add(pause, resume, cancel);
            return btns;
        })).setHeader("Actions").setWidth("240px");

        grid.setSizeFull();
        refreshJobsGrid(grid);

        Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH),
                e -> refreshJobsGrid(grid));
        layout.add(new H3("Crawl Jobs"), refresh, grid);
        return layout;
    }

    private void refreshJobsGrid(Grid<CrawlJob> grid) {
        try { grid.setItems(jobService.listAll()); }
        catch (Exception ex) { showNotification("Load failed: " + ex.getMessage(), true); }
    }

    private String renderBudget(int v) { return v < 0 ? "∞" : String.valueOf(v); }

    // -----------------------------------------------------------------
    // Feeds tab
    // -----------------------------------------------------------------

    private Component createFeedsContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        Grid<Feed> grid = new Grid<>(Feed.class, false);

        // -- Add form --
        TextField urlField = new TextField("Feed URL");
        urlField.setPlaceholder("https://example.com/feed.xml");
        urlField.setWidthFull();
        TextField titleField = new TextField("Title");
        titleField.setWidth("22%");
        TextField packField = new TextField("Pack");
        packField.setPlaceholder("tech / news / …");
        packField.setWidth("18%");
        IntegerField intervalField = intField("Poll interval (s)", 900, 30, 24 * 3600);
        intervalField.setWidth("18%");
        Checkbox adaptive = new Checkbox("Adaptive backoff");
        adaptive.setValue(true);
        Checkbox follow = new Checkbox("Follow articles");
        follow.setValue(false);
        Button addButton = new Button("Subscribe", new Icon(VaadinIcon.PLUS_CIRCLE));
        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addButton.addClickListener(e -> subscribeFeed(
                urlField.getValue(), titleField.getValue(), packField.getValue(),
                intervalField.getValue(), adaptive.getValue(), follow.getValue(), grid));

        HorizontalLayout row1 = new HorizontalLayout(urlField);
        row1.setWidthFull();
        HorizontalLayout row2 = new HorizontalLayout(titleField, packField, intervalField, adaptive, follow, addButton);
        row2.setWidthFull();
        row2.setAlignItems(FlexComponent.Alignment.END);

        // -- Grid --
        grid.addColumn(f -> f.title() == null || f.title().isBlank() ? f.url() : f.title())
                .setHeader("Title").setFlexGrow(2);
        grid.addColumn(Feed::url).setHeader("URL").setFlexGrow(3);
        grid.addColumn(f -> f.pack() == null ? "" : f.pack()).setHeader("Pack").setWidth("100px");
        grid.addColumn(f -> f.status().name()).setHeader("Status").setWidth("100px");
        grid.addColumn(f -> f.pollIntervalSeconds() + "s").setHeader("Interval").setWidth("100px");
        grid.addColumn(f -> f.followArticles() ? "yes" : "no").setHeader("Follow").setWidth("90px");
        grid.addColumn(f -> f.lastPolledAt() == null ? "never"
                        : DateTimeFormatter.ofPattern("HH:mm:ss")
                                .format(f.lastPolledAt().atZone(ZoneId.systemDefault())))
                .setHeader("Last polled").setWidth("120px");
        grid.addColumn(f -> f.consecutiveErrors() > 0
                        ? "err×" + f.consecutiveErrors()
                        : (f.consecutiveEmpty() > 0 ? "empty×" + f.consecutiveEmpty() : ""))
                .setHeader("Streak").setWidth("110px");
        grid.addColumn(new ComponentRenderer<>(feed -> {
            HorizontalLayout btns = new HorizontalLayout();
            Button viewItems = new Button("Items", e -> showFeedItemsDialog(feed));
            Button pollNow = new Button("Poll", e -> {
                try { feedPoller.poll(feed); refreshFeedsGrid(grid);
                    showNotification("Polled " + feed.url(), false);
                } catch (Exception ex) { showNotification("Poll failed: " + ex.getMessage(), true); }
            });
            Button pauseBtn = new Button(feed.status() == Feed.Status.PAUSED ? "Resume" : "Pause", e -> {
                feedRepo.updateStatus(feed.feedId(),
                        feed.status() == Feed.Status.PAUSED ? Feed.Status.ACTIVE : Feed.Status.PAUSED);
                refreshFeedsGrid(grid);
            });
            Button deleteBtn = new Button("Delete", e -> {
                feedRepo.delete(feed.feedId());
                refreshFeedsGrid(grid);
                showNotification("Deleted " + feed.url(), false);
            });
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            viewItems.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            pollNow.addThemeVariants(ButtonVariant.LUMO_SMALL);
            pauseBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            btns.add(viewItems, pollNow, pauseBtn, deleteBtn);
            return btns;
        })).setHeader("Actions").setWidth("320px");
        grid.setSizeFull();

        refreshFeedsGrid(grid);

        Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH),
                e -> refreshFeedsGrid(grid));

        VerticalLayout form = new VerticalLayout(new H3("Subscribe to a feed"), row1, row2);
        form.setPadding(true);
        form.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius)");

        layout.add(form, new Hr(), refresh, grid);
        return layout;
    }

    private void subscribeFeed(String url, String title, String pack, Integer interval,
                               boolean adaptive, boolean follow, Grid<Feed> grid) {
        if (url == null || url.isBlank()) {
            showNotification("Feed URL is required", true);
            return;
        }
        try {
            java.time.Instant now = java.time.Instant.now();
            Feed feed = new Feed(
                    UUID.randomUUID(), url.trim(),
                    title == null || title.isBlank() ? null : title.trim(),
                    pack == null || pack.isBlank() ? null : pack.trim(),
                    interval == null || interval < 30 ? 900 : interval,
                    adaptive, follow, false,
                    Feed.Status.ACTIVE, null, null, null,
                    now, 0, 0, now, now);
            feedRepo.create(feed);
            refreshFeedsGrid(grid);
            showNotification("Subscribed " + url + " — the poller will pick it up shortly", false);
        } catch (Exception ex) {
            showNotification("Subscribe failed: " + ex.getMessage(), true);
        }
    }

    private void refreshFeedsGrid(Grid<Feed> grid) {
        try { grid.setItems(feedRepo.listAll()); }
        catch (Exception ex) { showNotification("Load feeds failed: " + ex.getMessage(), true); }
    }

    private void showFeedItemsDialog(Feed feed) {
        Dialog dialog = new Dialog();
        dialog.setWidth("80%");
        dialog.setHeight("80%");
        dialog.setDraggable(true);
        dialog.setResizable(true);

        VerticalLayout content = new VerticalLayout();
        content.setSizeFull();
        content.setPadding(false);

        content.add(new H3("Items — " + (feed.title() == null ? feed.url() : feed.title())));
        Paragraph meta = new Paragraph(feed.url());
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)").set("margin", "0");
        content.add(meta);

        Grid<FeedItem> itemGrid = new Grid<>(FeedItem.class, false);
        itemGrid.addColumn(FeedItem::title).setHeader("Title").setFlexGrow(3);
        itemGrid.addColumn(item -> item.publishedAt() == null ? ""
                        : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .format(item.publishedAt().atZone(ZoneId.systemDefault())))
                .setHeader("Published").setWidth("140px");
        itemGrid.addColumn(item -> item.firstSeen() == null ? ""
                        : DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                                .format(item.firstSeen().atZone(ZoneId.systemDefault())))
                .setHeader("First seen").setWidth("130px");
        itemGrid.addColumn(item -> item.author() == null ? ""
                        : item.author().trim().length() > 30
                                ? item.author().trim().substring(0, 30) + "…"
                                : item.author().trim())
                .setHeader("Author").setWidth("160px");
        itemGrid.addColumn(new ComponentRenderer<>(item -> {
            if (item.url() == null) return new Span("");
            Button open = new Button(item.url(),
                    e -> UI.getCurrent().getPage().open(item.url(), "_blank"));
            open.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            return open;
        })).setHeader("URL").setFlexGrow(2);
        itemGrid.setSizeFull();

        try {
            List<FeedItem> items = feedRepo.recentItems(feed.feedId(), 100);
            itemGrid.setItems(items);
            content.add(new Span(items.size() + " item(s) shown (up to 100)"));
        } catch (Exception ex) {
            content.add(new Paragraph("Failed to load items: " + ex.getMessage()));
        }
        content.add(itemGrid);
        content.setFlexGrow(1, itemGrid);

        Button close = new Button("Close", e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        content.add(close);

        dialog.add(content);
        dialog.open();
    }

    // -----------------------------------------------------------------
    // Feed Packs tab
    // -----------------------------------------------------------------

    private Component createFeedPacksContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);
        layout.add(new H3("Curated feed packs"),
                new Paragraph("Subscribing to a pack adds every feed in it "
                        + "(idempotent — feeds already subscribed are skipped)."));

        List<FeedPack> packs;
        try { packs = feedPacks.listAll(); }
        catch (Exception ex) {
            showNotification("Load packs failed: " + ex.getMessage(), true);
            return layout;
        }

        HorizontalLayout cards = new HorizontalLayout();
        cards.setWidthFull();
        cards.getStyle().set("flex-wrap", "wrap").set("gap", "1rem");

        for (FeedPack pack : packs) {
            VerticalLayout card = new VerticalLayout();
            card.setWidth("280px");
            card.getStyle()
                    .set("border", "1px solid var(--lumo-contrast-20pct)")
                    .set("border-radius", "var(--lumo-border-radius)")
                    .set("padding", "1rem")
                    .set("background", "var(--lumo-contrast-5pct)");
            card.add(new H3(pack.name()));
            if (pack.description() != null) card.add(new Paragraph(pack.description()));
            card.add(new Span(pack.feeds().size() + " feed(s) in this pack"));
            Div feedList = new Div();
            feedList.getStyle().set("font-size", "12px")
                    .set("color", "var(--lumo-secondary-text-color)");
            StringBuilder sb = new StringBuilder();
            for (var m : pack.feeds()) {
                sb.append("• ").append(m.title() == null ? m.url() : m.title()).append('\n');
            }
            feedList.setText(sb.toString());
            feedList.getStyle().set("white-space", "pre-wrap");
            card.add(feedList);

            Button subscribe = new Button("Subscribe to '" + pack.name() + "'",
                    new Icon(VaadinIcon.RSS));
            subscribe.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            subscribe.addClickListener(e -> {
                try {
                    List<Feed> created = feedPacks.subscribeAll(pack.id());
                    showNotification(created.size() + " new feed(s) subscribed from '"
                            + pack.name() + "' (" + (pack.feeds().size() - created.size())
                            + " already present)", false);
                } catch (Exception ex) {
                    showNotification("Subscribe failed: " + ex.getMessage(), true);
                }
            });
            card.add(subscribe);
            cards.add(card);
        }

        layout.add(cards);
        return layout;
    }

    // -----------------------------------------------------------------
    // Stats tab — counters, unhealthy-feeds list, filterable console,
    // NDJSON download buttons
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Component createStatsContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setSpacing(true);

        // -- Counter cards --
        HorizontalLayout cards = new HorizontalLayout();
        cards.setWidthFull();
        cards.getStyle().set("flex-wrap", "wrap").set("gap", "1rem");
        Map<String, Object> summary;
        try { summary = statsService.summary(); }
        catch (Exception ex) {
            layout.add(new Paragraph("Stats unavailable: " + ex.getMessage()));
            return layout;
        }

        Object pagesTotal = summary.get("pages_total");
        Object feedItemsTotal = summary.get("feed_items_total");
        Map<String, Object> feeds = (Map<String, Object>) summary.getOrDefault("feeds", Map.of());
        Map<String, Object> jobs = (Map<String, Object>) summary.getOrDefault("jobs", Map.of());
        Map<String, Object> act = (Map<String, Object>) summary.getOrDefault("activity", Map.of());

        cards.add(counterCard("Pages", String.valueOf(pagesTotal), "crawled and stored"));
        cards.add(counterCard("Feed items", String.valueOf(feedItemsTotal),
                "unique items across all feeds"));
        cards.add(counterCard("Feeds", String.valueOf(feeds.getOrDefault("total", 0)),
                summaryOfStatusMap((Map<String, Object>) feeds.get("by_status"))));
        cards.add(counterCard("Jobs", String.valueOf(jobs.getOrDefault("total", 0)),
                summaryOfStatusMap((Map<String, Object>) jobs.get("by_status"))));
        cards.add(counterCard("Rate", act.getOrDefault("pages_per_minute", 0) + " pages/min",
                act.getOrDefault("crawled", 0) + " crawled, "
              + act.getOrDefault("rejected", 0) + " rejected in last 5m"));
        layout.add(cards);

        // -- Unhealthy feeds --
        List<Map<String, Object>> unhealthy = (List<Map<String, Object>>)
                summary.getOrDefault("unhealthy_feeds", List.of());
        if (!unhealthy.isEmpty()) {
            layout.add(new H3("Feeds needing attention"));
            Grid<Map<String, Object>> unhealthyGrid = new Grid<>();
            unhealthyGrid.addColumn(m -> m.get("title")).setHeader("Feed").setFlexGrow(2);
            unhealthyGrid.addColumn(m -> m.get("status")).setHeader("Status").setWidth("110px");
            unhealthyGrid.addColumn(m -> m.get("consecutive_errors"))
                    .setHeader("Err streak").setWidth("100px");
            unhealthyGrid.addColumn(m -> m.get("consecutive_empty"))
                    .setHeader("Empty streak").setWidth("110px");
            unhealthyGrid.addColumn(m -> {
                Object lp = m.get("last_polled_at");
                if (lp == null) return "never";
                return String.valueOf(lp).substring(0, Math.min(19, String.valueOf(lp).length()));
            }).setHeader("Last polled").setWidth("180px");
            unhealthyGrid.setItems(unhealthy);
            unhealthyGrid.setHeight("240px");
            layout.add(unhealthyGrid);
        }

        // -- Filterable activity console --
        layout.add(new H3("Activity console"));
        Checkbox showCrawled = new Checkbox("crawled", true);
        Checkbox showRejected = new Checkbox("rejected", true);
        Checkbox showErrors = new Checkbox("errors", true);
        TextField filterField = new TextField();
        filterField.setPlaceholder("filter by URL substring");
        filterField.setWidth("260px");
        Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
        HorizontalLayout ctrls = new HorizontalLayout(showCrawled, showRejected, showErrors, filterField, refresh);
        ctrls.setAlignItems(FlexComponent.Alignment.END);

        Div console = new Div();
        console.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius)")
                .set("padding", "1rem")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("font-family", "monospace")
                .set("font-size", "12px")
                .set("white-space", "pre-wrap")
                .set("min-height", "300px")
                .set("max-height", "500px")
                .set("overflow", "auto");

        Runnable render = () -> renderActivityConsole(console,
                showCrawled.getValue(), showRejected.getValue(), showErrors.getValue(),
                filterField.getValue());
        refresh.addClickListener(e -> render.run());
        showCrawled.addValueChangeListener(e -> render.run());
        showRejected.addValueChangeListener(e -> render.run());
        showErrors.addValueChangeListener(e -> render.run());
        filterField.addValueChangeListener(e -> render.run());
        render.run();
        layout.add(ctrls, console);

        // -- Export buttons --
        layout.add(new H3("Export (NDJSON / JSONL)"));
        Anchor pagesExport = new Anchor("/api/export/pages.ndjson?limit=100000", "");
        pagesExport.getElement().setAttribute("download", true);
        pagesExport.add(new Button("Download pages.ndjson", new Icon(VaadinIcon.DOWNLOAD)));
        Anchor itemsExport = new Anchor("/api/export/feed_items.ndjson?limit=100000", "");
        itemsExport.getElement().setAttribute("download", true);
        itemsExport.add(new Button("Download feed_items.ndjson", new Icon(VaadinIcon.DOWNLOAD)));
        Paragraph note = new Paragraph(
                "Streamed via chunked transfer — safe for large exports. "
              + "REST equivalents documented at /swagger-ui.html under \"Export\".");
        note.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "12px");
        HorizontalLayout exports = new HorizontalLayout(pagesExport, itemsExport);
        layout.add(exports, note);
        return layout;
    }

    private void renderActivityConsole(Div console, boolean crawled, boolean rejected,
                                       boolean errors, String urlFilter) {
        String needle = urlFilter == null ? "" : urlFilter.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ActivityFeed.Event e : activityFeed.recent(200)) {
            if (e.kind() == ActivityFeed.Kind.CRAWLED && !crawled) continue;
            if (e.kind() == ActivityFeed.Kind.REJECTED && !rejected) continue;
            if (e.kind() == ActivityFeed.Kind.ERROR && !errors) continue;
            if (!needle.isEmpty()
                    && (e.url() == null || !e.url().toLowerCase().contains(needle))) continue;
            String stamp = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(e.at().atZone(ZoneId.systemDefault()));
            String icon = switch (e.kind()) {
                case CRAWLED -> "✓";
                case REJECTED -> "✗";
                case ERROR -> "!";
            };
            sb.append(stamp).append(" ").append(icon).append(" ")
                    .append(e.url()).append("  ").append(e.detail()).append('\n');
            shown++;
        }
        if (shown == 0) sb.append("No matching events. Adjust filters or wait for activity.");
        else sb.insert(0, shown + " event(s) shown\n\n");
        console.setText(sb.toString());
    }

    private Component counterCard(String label, String value, String subtitle) {
        VerticalLayout card = new VerticalLayout();
        card.setWidth("220px");
        card.setPadding(true);
        card.setSpacing(false);
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius)")
                .set("background", "var(--lumo-contrast-5pct)");
        Span labelSpan = new Span(label);
        labelSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "12px").set("text-transform", "uppercase");
        H3 valueSpan = new H3(value);
        valueSpan.getStyle().set("margin", "0.25rem 0");
        Span subtitleSpan = new Span(subtitle == null ? "" : subtitle);
        subtitleSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "12px");
        card.add(labelSpan, valueSpan, subtitleSpan);
        return card;
    }

    private String summaryOfStatusMap(Map<String, Object> byStatus) {
        if (byStatus == null || byStatus.isEmpty()) return "";
        return byStatus.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey().toLowerCase())
                .collect(Collectors.joining(", "));
    }

    private Component createQueryContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        H3 queryHeader = new H3("Database Query");

        contentTypeCombo = new ComboBox<>("Content type");
        contentTypeCombo.setItems("Web pages", "Feed items");
        contentTypeCombo.setValue("Web pages");
        contentTypeCombo.setWidth("180px");
        contentTypeCombo.addValueChangeListener(e -> performSearch());

        searchField = new TextField("Search (leave blank to browse latest)");
        searchField.setPlaceholder("keyword — matches URL for pages, title/url/summary for feed items");
        searchField.setWidthFull();
        searchField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, e -> performSearch());

        Button searchButton = new Button("Search", new Icon(VaadinIcon.SEARCH));
        searchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        searchButton.addClickListener(e -> performSearch());

        Button refreshButton = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
        refreshButton.addClickListener(e -> performSearch());

        Anchor exportPages = new Anchor("/api/export/pages.ndjson?limit=100000", "");
        exportPages.getElement().setAttribute("download", true);
        exportPages.add(new Button("Export pages.ndjson", new Icon(VaadinIcon.DOWNLOAD)));
        Anchor exportItems = new Anchor("/api/export/feed_items.ndjson?limit=100000", "");
        exportItems.getElement().setAttribute("download", true);
        exportItems.add(new Button("Export feed_items.ndjson", new Icon(VaadinIcon.DOWNLOAD)));

        HorizontalLayout searchLayout = new HorizontalLayout(
                contentTypeCombo, searchField, searchButton, refreshButton,
                exportPages, exportItems);
        searchLayout.setWidthFull();
        searchLayout.setFlexGrow(1, searchField);
        searchLayout.setAlignItems(FlexComponent.Alignment.END);

        resultsCountSpan = new Span("");
        resultsCountSpan.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "12px");

        // Two grids, one visible at a time based on content type.
        pageGrid = createPageGrid();
        feedItemGrid = createFeedItemGrid();

        layout.add(queryHeader, searchLayout, resultsCountSpan, pageGrid, feedItemGrid);
        layout.setFlexGrow(1, pageGrid);
        layout.setFlexGrow(1, feedItemGrid);

        // Populate on first open so users see something without clicking.
        performSearch();
        return layout;
    }

    private Grid<FeedItem> createFeedItemGrid() {
        Grid<FeedItem> grid = new Grid<>(FeedItem.class, false);
        grid.addColumn(FeedItem::title).setHeader("Title").setFlexGrow(3);
        grid.addColumn(item -> item.publishedAt() == null ? ""
                        : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .format(item.publishedAt().atZone(ZoneId.systemDefault())))
                .setHeader("Published").setWidth("140px");
        grid.addColumn(item -> item.author() == null ? ""
                        : item.author().trim().length() > 30
                                ? item.author().trim().substring(0, 30) + "…"
                                : item.author().trim())
                .setHeader("Author").setWidth("160px");
        grid.addColumn(item -> item.categories() == null ? ""
                        : String.join(", ", item.categories()))
                .setHeader("Categories").setFlexGrow(1);
        grid.addColumn(new ComponentRenderer<>(item -> {
            if (item.url() == null) return new Span("");
            Button open = new Button(item.url(),
                    e -> UI.getCurrent().getPage().open(item.url(), "_blank"));
            open.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            return open;
        })).setHeader("URL").setFlexGrow(2);
        grid.setSizeFull();
        return grid;
    }
    
    private Grid<StorageService.PageMetadata> createPageGrid() {
        Grid<StorageService.PageMetadata> grid = new Grid<>(StorageService.PageMetadata.class, false);
        
        // URL column with click to view content
        grid.addColumn(new ComponentRenderer<>(page -> {
            Button urlButton = new Button(page.url(), new Icon(VaadinIcon.EXTERNAL_LINK));
            urlButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            urlButton.addClickListener(e -> showPageContent(page));
            return urlButton;
        }))
        .setHeader("URL (Click to View Content)")
        .setFlexGrow(3)
        .setSortable(false);
        
        grid.addColumn(StorageService.PageMetadata::httpStatus)
            .setHeader("Status")
            .setWidth("80px")
            .setSortable(true);
            
        grid.addColumn(page -> {
            if (page.fetchTime() == null) return "";
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(page.fetchTime().atZone(ZoneId.systemDefault()));
        })
        .setHeader("Crawled At")
        .setWidth("160px")
        .setSortable(true);
        
        grid.addColumn(page -> page.links() != null ? page.links().size() : 0)
            .setHeader("Links")
            .setWidth("80px")
            .setSortable(true);
            
        grid.addColumn(page -> {
            if (page.contentHash() != null && page.contentHash().length() > 12) {
                return page.contentHash().substring(0, 12) + "...";
            }
            return page.contentHash() != null ? page.contentHash() : "N/A";
        })
        .setHeader("Content Hash")
        .setWidth("120px");
        
        grid.setSizeFull();
        return grid;
    }
    
    private void toggleCrawler() {
        try {
            var stats = crawlerService.getCrawlerStats();
            if (stats.isRunning()) {
                crawlerService.stopCrawler();
                showNotification("Crawler stopped", false);
            } else {
                crawlerService.startCrawler();
                showNotification("Crawler started", false);
            }
            updateDashboard();
        } catch (Exception e) {
            showNotification("Error: " + e.getMessage(), true);
        }
    }
    
    private void performSearch() {
        if (searchField == null || contentTypeCombo == null) return;
        String searchTerm = searchField.getValue() == null ? "" : searchField.getValue().trim();
        String type = contentTypeCombo.getValue();
        boolean feedItems = "Feed items".equals(type);

        // Toggle which grid is visible.
        if (pageGrid != null) pageGrid.setVisible(!feedItems);
        if (feedItemGrid != null) feedItemGrid.setVisible(feedItems);

        try {
            if (feedItems) {
                List<FeedItem> items = searchTerm.isEmpty()
                        ? feedRepo.recentAllItems(200)
                        : feedRepo.searchAllItems(searchTerm, 200);
                feedItemGrid.setItems(items);
                if (resultsCountSpan != null) {
                    resultsCountSpan.setText(items.size() + " feed item(s)"
                            + (searchTerm.isEmpty() ? " — showing latest" : " — matching '" + searchTerm + "'"));
                }
            } else {
                var future = searchTerm.isEmpty()
                        ? storageService.getAllPages(200, 0)
                        : storageService.searchPages(searchTerm, 200);
                future.thenAccept(pages -> getUI().ifPresent(ui -> ui.access(() -> {
                    pageGrid.setItems(pages);
                    if (resultsCountSpan != null) {
                        resultsCountSpan.setText(pages.size() + " page(s)"
                                + (searchTerm.isEmpty() ? " — showing latest"
                                        : " — matching '" + searchTerm + "'"));
                    }
                })));
            }
        } catch (Exception e) {
            showNotification("Search error: " + e.getMessage(), true);
        }
    }

    private void refreshGrid() {
        performSearch();
    }
    
    private void showPageContent(StorageService.PageMetadata page) {
        Dialog dialog = new Dialog();
        dialog.setWidth("80%");
        dialog.setHeight("80%");
        dialog.setDraggable(true);
        dialog.setResizable(true);
        
        VerticalLayout content = new VerticalLayout();
        content.setSizeFull();
        
        // Header with page info
        H3 title = new H3("Page Content: " + page.url());
        String fetchedText = page.fetchTime() == null
                ? "n/a"
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .format(page.fetchTime().atZone(ZoneId.systemDefault()));
        Paragraph info = new Paragraph(String.format(
            "Status: %d | Fetched: %s | Hash: %s | Links: %d",
            page.httpStatus(),
            fetchedText,
            page.contentHash() == null ? "n/a" : page.contentHash(),
            page.links() != null ? page.links().size() : 0
        ));
        
        // Content area
        Div contentDiv = new Div();
        contentDiv.setText("Loading content from S3...");
        contentDiv.getStyle()
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("padding", "1rem")
            .set("background", "var(--lumo-contrast-5pct)")
            .set("font-family", "monospace")
            .set("white-space", "pre-wrap")
            .set("overflow", "auto")
            .set("height", "400px");
        
        // Load content from S3
        try {
            storageService.retrieve(page.url()).thenAccept(optionalContent -> {
                getUI().ifPresent(ui -> ui.access(() -> {
                    if (optionalContent.isPresent()) {
                        String htmlContent = optionalContent.get().content();
                        // Truncate very long content
                        if (htmlContent.length() > 50000) {
                            htmlContent = htmlContent.substring(0, 50000) + "\n\n... [Content truncated - showing first 50,000 characters] ...";
                        }
                        contentDiv.setText(htmlContent);
                    } else {
                        contentDiv.setText("Content not found in storage");
                    }
                }));
            });
        } catch (Exception e) {
            contentDiv.setText("Error loading content: " + e.getMessage());
        }
        
        Button closeButton = new Button("Close", e -> dialog.close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        
        content.add(title, info, contentDiv, closeButton);
        content.setFlexGrow(1, contentDiv);
        
        dialog.add(content);
        dialog.open();
    }
    
    private void updateDashboard() {
        try {
            var stats = crawlerService.getCrawlerStats();
            
            if (statusSpan != null) {
                if (stats.isRunning()) {
                    statusSpan.setText("🟢 Running");
                    if (startStopButton != null) {
                        startStopButton.setText("Stop Crawler");
                        startStopButton.removeThemeVariants(ButtonVariant.LUMO_SUCCESS);
                        startStopButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
                    }
                } else {
                    statusSpan.setText("🔴 Stopped");
                    if (startStopButton != null) {
                        startStopButton.setText("Start Crawler");
                        startStopButton.removeThemeVariants(ButtonVariant.LUMO_ERROR);
                        startStopButton.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
                    }
                }
                
                if (uptimeSpan != null) {
                    long hours = stats.uptime().toHours();
                    long minutes = stats.uptime().toMinutesPart();
                    long seconds = stats.uptime().toSecondsPart();
                    uptimeSpan.setText(String.format("⏱️ Uptime: %02d:%02d:%02d", hours, minutes, seconds));
                }
                
                if (pageCountSpan != null) {
                    crawlerService.getPageCount().thenAccept(count -> {
                        getUI().ifPresent(ui -> ui.access(() -> {
                            pageCountSpan.setText("📄 Pages Crawled: " + count);
                        }));
                    });
                }
            }
            
        } catch (Exception e) {
            showNotification("Error updating dashboard: " + e.getMessage(), true);
        }
    }
    
    private void showNotification(String message, boolean isError) {
        Notification notification = new Notification(message);
        notification.setDuration(isError ? 5000 : 3000);
        notification.setPosition(Notification.Position.TOP_CENTER);
        if (isError) {
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        } else {
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
        notification.open();
    }
    
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // Auto-refresh dashboard every 3 seconds — status + activity feed.
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.scheduleAtFixedRate(() -> {
            getUI().ifPresent(ui -> ui.access((Command) () -> {
                updateDashboard();
                updateActivity();
            }));
        }, 3, 3, TimeUnit.SECONDS);
    }
    
    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
