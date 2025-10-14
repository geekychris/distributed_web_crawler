# 🚀 Quick Start Guide - Distributed Crawler with Swagger

## Start the Application

```bash
# Build the application
mvn clean package

# Run the Spring Boot application
java -jar target/distributed-crawler-1.0-SNAPSHOT.jar
```

## 🎯 **Immediate Access Points**

Once started, visit these URLs:

### **📚 Interactive API Documentation**
- **Swagger UI**: http://localhost:8080/swagger-ui.html
  - Test all API endpoints directly from your browser
  - See request/response examples
  - Explore API schemas interactively

### **🔧 API Endpoints**
- **Crawler Control**: http://localhost:8080/api/crawler/status
- **System Info**: http://localhost:8080/api/system/info  
- **Health Check**: http://localhost:8080/actuator/health

### **📄 API Specifications**  
- **OpenAPI JSON**: http://localhost:8080/api-docs
- **OpenAPI YAML**: http://localhost:8080/api-docs.yaml

## 🎮 **Try It Out**

### **Using Swagger UI** (Recommended)
1. Go to http://localhost:8080/swagger-ui.html
2. Expand "Crawler Management" section
3. Click on `POST /api/crawler/start`
4. Click "Try it out" → "Execute"
5. See the response!

### **Using curl**
```bash
# Check crawler status
curl http://localhost:8080/api/crawler/status

# Get system information
curl http://localhost:8080/api/system/info

# Start the crawler
curl -X POST http://localhost:8080/api/crawler/start

# Stop the crawler  
curl -X POST http://localhost:8080/api/crawler/stop
```

## ✨ **What's New**

- **🎯 Interactive API Documentation** - Test endpoints directly in browser
- **📖 Comprehensive Schemas** - See all request/response formats  
- **🔍 Real-time Testing** - Execute API calls and see live responses
- **📋 API Discovery** - Browse all available endpoints organized by category
- **⚡ Developer Experience** - No need for external API clients

## 🏗️ **Architecture Overview**

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                    │
├─────────────────────────────────────────────────────────────┤  
│  📚 Swagger UI          │  🔧 REST APIs          │  📊 Actuator  │
│  /swagger-ui.html       │  /api/crawler/*        │  /actuator/*   │
│  /api-docs              │  /api/system/*         │               │
├─────────────────────────────────────────────────────────────┤
│              🚀 Distributed Web Crawler Service              │
├─────────────────────────────────────────────────────────────┤
│  📨 Kafka Queue  │  🗄️ Cassandra DB  │  🪣 S3 Storage  │  🌐 Web  │
└─────────────────────────────────────────────────────────────┘
```

**Perfect for**: API development, testing, documentation, and team collaboration!

---

**💡 Pro Tip**: Bookmark `http://localhost:8080/swagger-ui.html` - it's your one-stop shop for API exploration and testing!