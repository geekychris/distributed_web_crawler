package com.webcrawler.startup;

import com.webcrawler.core.WebCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Automatically starts the web crawler when the application starts up.
 */
@Component
public class CrawlerAutostart implements ApplicationRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(CrawlerAutostart.class);
    
    private final WebCrawler webCrawler;
    
    @Autowired
    public CrawlerAutostart(WebCrawler webCrawler) {
        this.webCrawler = webCrawler;
    }
    
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        logger.info("🚀 Auto-starting web crawler...");
        try {
            webCrawler.start();
            logger.info("✅ Web crawler started successfully!");
        } catch (Exception e) {
            logger.error("❌ Failed to start web crawler", e);
            throw e;
        }
    }
}