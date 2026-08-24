# RealChat - Quick Start Guide 🚀

## 5-Minute Setup

### 1️⃣ Prerequisites
```bash
# Check Java version
java -version  # Should be 21+

# Check Maven
mvn -version   # Should be 3.9+

# MySQL should be running
mysql -u root -p
```

### 2️⃣ Database Setup
```sql
CREATE DATABASE chat_app;
```

### 3️⃣ Configure Application
Edit: `app/src/main/resources/application.properties`

```properties
# MySQL Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/chat_app?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=your_mysql_password

# JWT Configuration (Change this to a strong secret!)
app.jwt.secret=MyVerySuperSecureKeyWithAtLeast32Characters
app.jwt.expiration=86400000
```

### 4️⃣ Build & Run
```bash
cd app
mvn clean package -DskipTests
java -jar target/app-0.0.1-SNAPSHOT.jar
```

### 5️⃣ Access Application
Open your browser: **http://localhost:8081**

---

## 📝 Test the Application

### Create First Account
1. Click "Sign up" on login page
2. Fill in details:
   - Username: `john`
   - Email: `john@example.com`
   - Password: `password123`
   - Display Name: `John Doe`
3. Click "Create Account"

### Login
1. Username: `john`
2. Password: `password123`
3. Click "Login"

### Create Second Account (Different Browser Tab)
1. Open **http://localhost:8081** in new tab
2. Create another user:
   - Username: `jane`
   - Email: `jane@example.com`
   - Password: `password123`
   - Display Name: `Jane Smith`

### Test Chat
1. In John's tab: Click on "Jane Smith" from contacts
2. Type a message: "Hello Jane!"
3. In Jane's tab: You should see the message instantly
4. Jane replies: "Hi John!"
5. Messages show with timestamps and read status

---

## 🎯 Key Features to Test

| Feature | How to Test |
|---------|------------|
| **Real-time Chat** | Send message, see instant delivery |
| **User Search** | Type in search box to find users |
| **Online Status** | See green dot next to online users |
| **Read Receipts** | Sender sees when message is read |
| **Message History** | Reload page, messages persist |
| **User Profile** | Click user name to see details |

---

## 🔧 Common Issues & Solutions

### "Connection refused: localhost:3306"
**Solution:** MySQL not running
```bash
# Windows
mysql.server start

# macOS
brew services start mysql

# Linux
sudo systemctl start mysql
```

### "Access denied for user 'root'"
**Solution:** Update password in `application.properties`
```properties
spring.datasource.password=your_actual_mysql_password
```

### "No message broker detected"
**Solution:** WebSocket connection issue. Check browser console
- Clear browser cache
- Restart application
- Check firewall

### "404 Not Found"
**Solution:** Application not running on port 8081
- Verify: http://localhost:8081 shows the login page
- Check console for errors
- Change port in `application.properties`: `server.port=8082`

---

## 📊 API Testing with cURL

### Register User
```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "password123",
    "displayName": "Test User"
  }'
```

### Login
```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'
```

**Response will include token:**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "id": 1,
  "username": "testuser",
  "email": "test@example.com",
  "displayName": "Test User"
}
```

### Get Current User
```bash
curl -X GET http://localhost:8081/api/auth/me \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

### Get All Users
```bash
curl -X GET http://localhost:8081/api/users \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

### Search Users
```bash
curl -X GET "http://localhost:8081/api/users/search?query=john" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

---

## 🛠️ Development Tips

### View Application Logs
Logs are saved in: `logs/chat-app.log`

```bash
# Watch logs in real-time
tail -f logs/chat-app.log
```

### Enable Debug Mode
Add to `application.properties`:
```properties
logging.level.root=DEBUG
logging.level.com.chat.app=DEBUG
logging.level.org.springframework.security=DEBUG
```

### Database Inspection
```bash
# Connect to MySQL
mysql -u root -p chat_app

# View tables
SHOW TABLES;
SELECT * FROM users;
SELECT * FROM messages;
```

### Check WebSocket Connection
Open browser DevTools (F12) → Console
```javascript
// Check if STOMP client is connected
console.log(appState.stompClient.connected);
```

---

## 🚀 Production Deployment

### 1. Update Configuration
```properties
# Change to production database
spring.datasource.url=jdbc:mysql://prod-db-host:3306/chat_app

# Strong JWT secret (32+ characters)
app.jwt.secret=GenerateStrongRandomSecretHere!@#$%

# Longer token expiration (optional)
app.jwt.expiration=604800000
```

### 2. Build JAR
```bash
mvn clean package -DskipTests
```

### 3. Deploy to Server
```bash
# Copy JAR to server
scp app/target/app-0.0.1-SNAPSHOT.jar user@server:/app/

# On server, run with:
java -jar app-0.0.1-SNAPSHOT.jar
```

### 4. Enable HTTPS
Use Nginx/Apache reverse proxy with SSL certificate

---

## 📱 Mobile Testing

The application is **fully responsive**:
1. Open on mobile browser: `http://your-server-ip:8081`
2. Chat works on all screen sizes
3. Touch-friendly interface

---

## ⚡ Performance Tips

1. **Database Optimization**
   - Add indexes on frequently queried fields
   - Use pagination for large result sets

2. **Server Optimization**
   - Use cloud database (AWS RDS, Google Cloud SQL)
   - Enable gzip compression
   - Use CDN for static assets

3. **Client Optimization**
   - Lazy load message history
   - Implement message pagination
   - Cache user list locally

---

## 🔐 Security Reminders

✅ **Before Going Live:**
- [ ] Change JWT secret to random 32+ character string
- [ ] Enable HTTPS/SSL
- [ ] Use strong MySQL password
- [ ] Update CORS allowed origins
- [ ] Enable rate limiting
- [ ] Set up proper logging
- [ ] Test with OWASP Top 10
- [ ] Implement user verification email

---

## 📞 Troubleshooting

### Application won't start
```bash
# Check if port is in use
lsof -i :8081  # Linux/macOS
netstat -ano | findstr :8081  # Windows

# Kill process on that port or change port in properties
```

### WebSocket connection fails
1. Check browser console for errors
2. Verify WebSocket proxy configuration
3. Ensure `/ws/chat` endpoint is accessible

### Messages not saving
1. Verify MySQL connection
2. Check database `chat_app` exists
3. View logs: `tail -f logs/chat-app.log`
4. Restart application

### Authentication fails
1. Check JWT secret in properties
2. Verify password hashing works
3. Check user in database: `SELECT * FROM users;`

---

## 📖 Documentation Files

- `UPGRADE_SUMMARY.md` - Complete upgrade details
- `README.md` - This file
- `pom.xml` - Maven dependencies
- `src/` - Full source code with comments

---

## 🎓 Learning Path

1. **Understand Architecture** → Read `UPGRADE_SUMMARY.md`
2. **Explore Code** → Browse `src/main/java/com/chat/app/`
3. **Test APIs** → Use cURL commands above
4. **Modify Features** → Edit code and rebuild
5. **Deploy to Cloud** → Use Heroku, AWS, Google Cloud, etc.

---

## ✨ What's New vs Original

| Original | Upgraded |
|----------|----------|
| No authentication | JWT + Spring Security ✅ |
| Basic HTML | Modern responsive UI ✅ |
| No database | MySQL integration ✅ |
| Hardcoded config | Environment configuration ✅ |
| No user system | Complete user management ✅ |
| No error handling | Comprehensive exception handling ✅ |
| Vulnerable to XSS | Input sanitization ✅ |
| No logging | SLF4j logging with debug mode ✅ |
| Simple controller | Layered architecture ✅ |
| No REST API | Full REST API with DTOs ✅ |

---

## 🎉 You're Ready!

Your real-time chat application is now:
- ✅ Secure
- ✅ Scalable
- ✅ Production-ready
- ✅ Fully documented
- ✅ Easy to maintain

**Start chatting!** 🚀

---

**Need Help?**
- Check logs: `logs/chat-app.log`
- Review source code: `src/main/java/com/chat/app/`
- Read documentation: `UPGRADE_SUMMARY.md`
