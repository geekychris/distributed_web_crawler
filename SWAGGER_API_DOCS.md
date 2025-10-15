# Swagger API Documentation Integration

## Overview

The distributed crawler Spring Boot application now includes comprehensive **Swagger/OpenAPI 3.0** documentation with an interactive web interface for API exploration and testing.

## 🚀 **Quick Access**

Once the application is running:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/api-docs
- **OpenAPI YAML**: http://localhost:8080/api-docs.yaml

## 📚 **API Documentation Features**

### **Interactive API Explorer**
- **Try It Out**: Test API endpoints directly from the browser
- **Request/Response Examples**: See sample payloads and responses  
- **Schema Validation**: Automatic request/response validation
- **Authentication Testing**: Test secured endpoints (when implemented)

### **Comprehensive Documentation**
- **Detailed Descriptions**: Every endpoint includes purpose and usage
- **Parameter Documentation**: All request parameters with examples
- **Response Schemas**: Complete response object documentation
- **Error Handling**: HTTP status codes and error response formats

## 🎯 **Available API Groups**

### **1. Crawler Management** (`/api/crawler`)
- `POST /api/crawler/start` - Start the web crawler
- `POST /api/crawler/stop` - Stop the web crawler  
- `GET /api/crawler/status` - Get current crawler status
- `GET /api/crawler/config` - Get crawler configuration

### **2. System Information** (`/api/system`)
- `GET /api/system/info` - Get system and JVM information
- `GET /api/system/health-simple` - Simple health check

### **3. Spring Boot Actuator** (`/actuator`)
- `GET /actuator/health` - Detailed health information
- `GET /actuator/info` - Application information
- `GET /actuator/metrics` - Application metrics

## 🔧 **Configuration**

### **application.yml Settings**
```yaml
# OpenAPI/Swagger configuration
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
    operations-sorter: method
    tags-sorter: alpha
    display-request-duration: true
  info:
    title: "Distributed Web Crawler API"
    description: "REST API for managing the distributed web crawler service"
    version: "1.0.0"
    contact:
      name: "Web Crawler Team"
      url: "https://github.com/your-org/distributed-crawler"
      email: "crawler-team@example.com"
```

### **Maven Dependencies**
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

## 📝 **API Usage Examples**

### **Using Swagger UI**
1. Navigate to http://localhost:8080/swagger-ui.html
2. Browse available endpoints by API group
3. Click "Try it out" on any endpoint
4. Fill in parameters (if required)
5. Click "Execute" to test the API
6. View the response with headers, status code, and body

### **Using curl with API Documentation**
```bash
# Start crawler
curl -X POST http://localhost:8080/api/crawler/start

# Check status  
curl -X GET http://localhost:8080/api/crawler/status

# Get system info
curl -X GET http://localhost:8080/api/system/info

# Stop crawler
curl -X POST http://localhost:8080/api/crawler/stop
```

## 🎨 **Swagger UI Features**

### **Enhanced UI Options**
- **Method Sorting**: Operations sorted by HTTP method
- **Tag Sorting**: API groups sorted alphabetically  
- **Request Duration**: Shows how long each request takes
- **Expandable Sections**: Click to expand/collapse API groups
- **Dark/Light Theme**: Automatic theme detection

### **Request Testing**
- **Parameter Input**: Interactive forms for request parameters
- **Request Body Editor**: JSON editor for request bodies
- **Response Viewer**: Formatted JSON response display
- **Copy as curl**: Generate curl commands from UI interactions

## 🔍 **API Schema Details**

### **Crawler Configuration Schema**
```json
{
  "maxDepth": 10,
  "crawlDelay": "PT1S",
  "maxConcurrentRequests": 100,
  "allowedDomains": ["example\\.com$"],
  "excludePatterns": ["/private/.*"],
  "seedUrls": ["https://example.com/"],
  "respectRobotsTxt": true,
  "userAgent": "DistributedCrawler/1.0"
}
```

### **Status Response Schema**
```json
{
  "running": true,
  "uptime": "PT2H30M15S",
  "configuration": {
    "maxDepth": 5,
    "maxConcurrentRequests": 10,
    "crawlDelay": "PT1S",
    "respectRobotsTxt": true,
    "userAgent": "DistributedCrawler/1.0",
    "seedUrlCount": 2
  }
}
```

## 🚀 **Development Workflow**

### **API-First Development**
1. Design APIs using OpenAPI annotations
2. Generate documentation automatically
3. Test endpoints using Swagger UI
4. Share API documentation with team
5. Version API changes through documentation

### **Testing Strategy**
1. **Manual Testing**: Use Swagger UI for interactive testing
2. **Automated Testing**: Use OpenAPI spec for contract testing
3. **Integration Testing**: Validate API responses against schemas
4. **Performance Testing**: Monitor request durations in Swagger UI

## 🔒 **Security Considerations**

### **Production Deployment**
```yaml
# Disable Swagger UI in production
springdoc:
  swagger-ui:
    enabled: ${SWAGGER_ENABLED:false}
  api-docs:
    enabled: ${API_DOCS_ENABLED:false}
```

### **Access Control**
- Consider adding authentication to `/swagger-ui.html`
- Restrict `/api-docs` endpoint in production
- Use API keys or OAuth for endpoint security

## 🎯 **Benefits**

1. **Developer Experience**: Interactive API testing and exploration
2. **Documentation**: Always up-to-date API documentation
3. **Team Collaboration**: Shared understanding of API contracts
4. **Quality**: Automatic validation and testing capabilities
5. **Integration**: Easy integration with CI/CD pipelines
6. **Standards**: OpenAPI 3.0 compliance for tooling compatibility

## 🔧 **Customization Options**

### **Custom Themes**
```yaml
springdoc:
  swagger-ui:
    css-url: /custom-swagger.css
```

### **Additional Servers**
```java
@Bean
public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .servers(List.of(
            new Server().url("http://localhost:8080").description("Development"),
            new Server().url("https://api.production.com").description("Production")
        ));
}
```

The Swagger integration provides a complete API documentation and testing solution that enhances both development productivity and API usability!