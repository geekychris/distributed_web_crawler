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
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Command;
import com.webcrawler.service.CrawlerUIService;
import com.webcrawler.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Route("")
@PageTitle("Distributed Web Crawler Dashboard")
public class MainView extends VerticalLayout {

    private final CrawlerUIService crawlerService;
    private final StorageService storageService;
    
    // Dashboard components
    private Button startStopButton;
    private Span statusSpan;
    private Span uptimeSpan;
    private Span pageCountSpan;
    
    // URL submission components
    private TextArea urlsTextArea;
    private TextField singleUrlField;
    
    // Database query components
    private TextField searchField;
    private Grid<StorageService.PageMetadata> pageGrid;
    
    // Auto-refresh
    private ScheduledExecutorService scheduler;
    
    @Autowired
    public MainView(CrawlerUIService crawlerService, StorageService storageService) {
        this.crawlerService = crawlerService;
        this.storageService = storageService;
        
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        
        // Create header
        add(createHeader());
        
        // Create tabbed interface
        Tabs tabs = new Tabs();
        Tab dashboardTab = new Tab("Dashboard");
        Tab addUrlsTab = new Tab("Add URLs");
        Tab queryTab = new Tab("Query Database");
        tabs.add(dashboardTab, addUrlsTab, queryTab);
        
        // Content area that changes based on selected tab
        Div contentArea = new Div();
        contentArea.setSizeFull();
        
        // Show dashboard by default
        contentArea.add(createDashboardContent());
        
        tabs.addSelectedChangeListener(event -> {
            contentArea.removeAll();
            Tab selectedTab = event.getSelectedTab();
            
            if (selectedTab == dashboardTab) {
                contentArea.add(createDashboardContent());
            } else if (selectedTab == addUrlsTab) {
                contentArea.add(createAddUrlsContent());
            } else if (selectedTab == queryTab) {
                contentArea.add(createQueryContent());
            }
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
        HorizontalLayout controls = new HorizontalLayout();
        startStopButton = new Button();
        startStopButton.addClickListener(e -> toggleCrawler());
        controls.add(startStopButton);
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
            
        layout.add(statusLayout);
        return layout;
    }
    
    private Component createAddUrlsContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSpacing(true);
        
        // Single URL input
        H3 singleUrlHeader = new H3("Add Single URL");
        singleUrlField = new TextField("URL to crawl");
        singleUrlField.setPlaceholder("https://example.com");
        singleUrlField.setWidthFull();
        
        Button addSingleButton = new Button("Add URL", new Icon(VaadinIcon.PLUS));
        addSingleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addSingleButton.addClickListener(e -> addSingleUrl());
        
        HorizontalLayout singleUrlLayout = new HorizontalLayout(singleUrlField, addSingleButton);
        singleUrlLayout.setWidthFull();
        singleUrlLayout.setFlexGrow(1, singleUrlField);
        singleUrlLayout.setAlignItems(FlexComponent.Alignment.END);
        
        // Bulk URL input
        H3 bulkUrlHeader = new H3("Add Multiple URLs");
        urlsTextArea = new TextArea("URLs (one per line)");
        urlsTextArea.setPlaceholder("https://example1.com\nhttps://example2.com\nhttps://example3.com");
        urlsTextArea.setWidthFull();
        urlsTextArea.setHeight("200px");
        
        Button addMultipleButton = new Button("Add All URLs", new Icon(VaadinIcon.UPLOAD));
        addMultipleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addMultipleButton.addClickListener(e -> addMultipleUrls());
        
        layout.add(
            singleUrlHeader,
            singleUrlLayout,
            new Hr(),
            bulkUrlHeader, 
            urlsTextArea,
            addMultipleButton
        );
        
        return layout;
    }
    
    private Component createQueryContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        
        // Search interface
        H3 queryHeader = new H3("Database Query");
        searchField = new TextField("Search URLs");
        searchField.setPlaceholder("Enter search term or leave empty to show all");
        searchField.setWidthFull();
        
        Button searchButton = new Button("Search", new Icon(VaadinIcon.SEARCH));
        searchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        searchButton.addClickListener(e -> performSearch());
        
        Button refreshButton = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
        refreshButton.addClickListener(e -> refreshGrid());
        
        HorizontalLayout searchLayout = new HorizontalLayout(searchField, searchButton, refreshButton);
        searchLayout.setWidthFull();
        searchLayout.setFlexGrow(1, searchField);
        searchLayout.setAlignItems(FlexComponent.Alignment.END);
        
        // Results grid
        pageGrid = createPageGrid();
        
        layout.add(queryHeader, searchLayout, pageGrid);
        return layout;
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
    
    private void addSingleUrl() {
        String url = singleUrlField.getValue().trim();
        if (url.isEmpty()) {
            showNotification("Please enter a URL", true);
            return;
        }
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        try {
            crawlerService.addSeedUrl(url).join();
            singleUrlField.clear();
            showNotification("URL added: " + url, false);
        } catch (Exception e) {
            showNotification("Error adding URL: " + e.getMessage(), true);
        }
    }
    
    private void addMultipleUrls() {
        String urlsText = urlsTextArea.getValue().trim();
        if (urlsText.isEmpty()) {
            showNotification("Please enter URLs to add", true);
            return;
        }
        
        String[] urls = urlsText.split("\n");
        int successCount = 0;
        int errorCount = 0;
        
        for (String url : urls) {
            url = url.trim();
            if (url.isEmpty()) continue;
            
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            
            try {
                crawlerService.addSeedUrl(url).join();
                successCount++;
            } catch (Exception e) {
                errorCount++;
            }
        }
        
        urlsTextArea.clear();
        showNotification(String.format("Added %d URLs successfully, %d failed", successCount, errorCount), errorCount > 0);
    }
    
    private void performSearch() {
        String searchTerm = searchField.getValue().trim();
        try {
            if (searchTerm.isEmpty()) {
                storageService.getAllPages(100, 0).thenAccept(pages -> {
                    getUI().ifPresent(ui -> ui.access(() -> pageGrid.setItems(pages)));
                });
            } else {
                storageService.searchPages(searchTerm, 100).thenAccept(pages -> {
                    getUI().ifPresent(ui -> ui.access(() -> pageGrid.setItems(pages)));
                });
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
        Paragraph info = new Paragraph(String.format(
            "Status: %d | Fetched: %s | Hash: %s | Links: %d",
            page.httpStatus(),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(page.fetchTime().atZone(ZoneId.systemDefault())),
            page.contentHash(),
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
        // Auto-refresh dashboard every 5 seconds
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.scheduleAtFixedRate(() -> {
            getUI().ifPresent(ui -> ui.access((Command) this::updateDashboard));
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
