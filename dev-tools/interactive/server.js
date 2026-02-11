#!/usr/bin/env node
// Простой HTTP сервер для Live Preview Cursor с Live Reload
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = 3000;
const PROJECT_ROOT = path.join(__dirname, '../..');

// WebSocket clients для live reload
const clients = new Set();

// Функция для отправки SSE клиентам
function notifyClients() {
    console.log(`🔄 Notifying ${clients.size} clients to reload...`);
    clients.forEach(client => {
        try {
            client.write('data: reload\n\n');
        } catch (err) {
            console.log('Client disconnected');
            clients.delete(client);
        }
    });
}

// Отслеживание изменений файлов
const watchedFiles = [
    path.join(__dirname, 'project-data.json'),
    path.join(__dirname, 'code-links.json'),
    path.join(__dirname, 'preview.html'),
    path.join(__dirname, 'index.html')
];

watchedFiles.forEach(file => {
    if (fs.existsSync(file)) {
        fs.watch(file, (eventType, filename) => {
            console.log(`📝 File changed: ${filename || file}`);
            notifyClients();
        });
        console.log(`👁️  Watching: ${path.basename(file)}`);
    }
});

const server = http.createServer((req, res) => {
    // Логируем все запросы
    console.log(`📥 ${req.method} ${req.url}`);

    // Обрабатываем корневой путь и пути с query параметрами
    const urlPath = req.url.split('?')[0]; // Убираем query параметры для определения пути

    // Обработка /events endpoint для Server-Sent Events (live reload)
    if (urlPath === '/events') {
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'Access-Control-Allow-Origin': '*'
        });

        // Добавляем клиента
        clients.add(res);
        console.log(`➕ Client connected (total: ${clients.size})`);

        // Отправляем initial heartbeat
        res.write('data: connected\n\n');

        // Удаляем клиента при отключении
        req.on('close', () => {
            clients.delete(res);
            console.log(`➖ Client disconnected (total: ${clients.size})`);
        });

        return;
    }

    // Обработка /preview endpoint для Flutter компонентов
    if (urlPath === '/preview') {
        const previewPath = path.join(__dirname, 'preview.html');
        fs.readFile(previewPath, (err, data) => {
            if (err) {
                res.writeHead(404);
                res.end('Preview page not found');
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'text/html',
                'Access-Control-Allow-Origin': '*'
            });
            res.end(data);
        });
        return;
    }
    
    // Обработка /flutter-app для Flutter Web приложения
    if (urlPath.startsWith('/flutter-app/')) {
        // Путь к собранному Flutter Web приложению
        const flutterWebPath = path.join(PROJECT_ROOT, 'build', 'web', urlPath.replace('/flutter-app', ''));
        
        // Если файл не существует, пробуем найти в web директории
        let filePath = flutterWebPath;
        if (!fs.existsSync(filePath)) {
            filePath = path.join(PROJECT_ROOT, 'web', urlPath.replace('/flutter-app', ''));
        }
        
        // Если все еще не существует, пробуем index.html
        if (!fs.existsSync(filePath) && urlPath === '/flutter-app/') {
            filePath = path.join(PROJECT_ROOT, 'build', 'web', 'index.html');
            if (!fs.existsSync(filePath)) {
                filePath = path.join(PROJECT_ROOT, 'web', 'index.html');
            }
        }
        
        fs.readFile(filePath, (err, data) => {
            if (err) {
                res.writeHead(404);
                res.end('Flutter app not found. Build it with: flutter build web');
                return;
            }
            
            // Определяем Content-Type
            const ext = path.extname(filePath);
            const contentTypes = {
                '.html': 'text/html',
                '.js': 'application/javascript',
                '.css': 'text/css',
                '.json': 'application/json',
                '.png': 'image/png',
                '.jpg': 'image/jpeg',
                '.jpeg': 'image/jpeg',
                '.svg': 'image/svg+xml',
                '.woff': 'font/woff',
                '.woff2': 'font/woff2',
                '.ttf': 'font/ttf',
                '.eot': 'application/vnd.ms-fontobject'
            };
            
            res.writeHead(200, {
                'Content-Type': contentTypes[ext] || 'application/octet-stream',
                'Access-Control-Allow-Origin': '*'
            });
            res.end(data);
        });
        return;
    }
    
    let filePath = (urlPath === '/' || urlPath === '') 
        ? path.join(__dirname, 'index.html')
        : path.join(PROJECT_ROOT, req.url.split('?')[0]); // Убираем query параметры из пути

    // Безопасность - проверяем что файл внутри проекта
    if (!filePath.startsWith(PROJECT_ROOT) && !filePath.startsWith(__dirname)) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
    }

    // Если файл не существует, пробуем найти в проекте
    if (!fs.existsSync(filePath) && req.url.startsWith('/src/')) {
        filePath = path.join(PROJECT_ROOT, req.url);
    }

    fs.readFile(filePath, (err, data) => {
        if (err) {
            res.writeHead(404);
            res.end('File not found');
            return;
        }

        // Определяем Content-Type
        const ext = path.extname(filePath);
        const contentTypes = {
            '.html': 'text/html',
            '.cljd': 'text/plain',
            '.js': 'application/javascript',
            '.css': 'text/css',
            '.json': 'application/json'
        };

        res.writeHead(200, {
            'Content-Type': contentTypes[ext] || 'text/plain',
            'Access-Control-Allow-Origin': '*'
        });
        res.end(data);
    });
});

server.listen(PORT, '127.0.0.1', () => {
    console.log(`✅ Server running at http://127.0.0.1:${PORT}/`);
    console.log(`📁 Serving from: ${__dirname}`);
    console.log(`🌐 Open: http://127.0.0.1:${PORT}/`);
    console.log(`   (index.html доступен по корневому пути /)`);
});

