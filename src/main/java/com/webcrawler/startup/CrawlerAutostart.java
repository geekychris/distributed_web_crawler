package com.webcrawler.startup;

import com.webcrawler.config.CrawlerProperties;
import com.webcrawler.core.WebCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Auto-starts the crawler on application boot when {@code crawler.autostart=true}
 * (the default). Failures are logged but never rethrown — a bad first crawl
 * should not prevent the REST API and Vaadin UI from coming up, so operators
 * can still fix config through the running app.
 */
@Component
public class CrawlerAutostart implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerAutostart.class);

    private final WebCrawler webCrawler;
    private final CrawlerProperties properties;

    @Autowired
    public CrawlerAutostart(WebCrawler webCrawler, CrawlerProperties properties) {
        this.webCrawler = webCrawler;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.autostart()) {
            logger.info("crawler.autostart=false — skipping auto-start");
            return;
        }
        try {
            webCrawler.start();
            logger.info("Web crawler auto-started");
        } catch (Exception e) {
            logger.error("Auto-start failed — start via /api/crawler/start or the UI", e);
        }
    }
}
