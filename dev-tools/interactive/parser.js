// Парсер структуры проекта для автоматического обновления дерева

async function parseProject() {
    const structure = {};
    
    // Список файлов проекта
    const files = [
        { path: 'src/bikes/core.cljd', type: 'file', name: 'core.cljd' },
        { path: 'src/bikes/app.cljd', type: 'file', name: 'app.cljd' },
        { path: 'src/bikes/screens/home.cljd', type: 'screen', name: 'home.cljd' },
        { path: 'src/bikes/screens/qr_scanner.cljd', type: 'screen', name: 'qr_scanner.cljd' },
        { path: 'src/bikes/screens/bike_rental.cljd', type: 'screen', name: 'bike_rental.cljd' },
        { path: 'src/bikes/components/pwa_install.cljd', type: 'component', name: 'pwa_install.cljd' },
        { path: 'src/bikes/services/api.cljd', type: 'service', name: 'api.cljd' },
        { path: 'src/bikes/services/bluetooth.cljd', type: 'service', name: 'bluetooth.cljd' },
        { path: 'src/bikes/state/app_state.cljd', type: 'state', name: 'app_state.cljd' },
        { path: 'src/bikes/utils/helpers.cljd', type: 'file', name: 'helpers.cljd' }
    ];
    
    // Парсинг каждого файла
    for (const file of files) {
        try {
            const content = await loadFile(file.path);
            if (content) {
                const functions = extractFunctions(content);
                file.functions = functions;
                file.description = extractDescription(content);
            }
        } catch (e) {
            console.error(`Error parsing ${file.path}:`, e);
        }
    }
    
    return buildTree(files);
}

function extractFunctions(content) {
    const functions = [];
    const lines = content.split('\n');
    
    lines.forEach((line, index) => {
        // Ищем определения функций
        const defMatch = line.match(/\(defn\s+([^\s\(]+)/);
        if (defMatch) {
            functions.push({
                name: defMatch[1],
                line: index + 1,
                description: extractFunctionDescription(lines, index)
            });
        }
    });
    
    return functions;
}

function extractDescription(content) {
    const nsMatch = content.match(/\(ns\s+[^\s]+\s+"([^"]+)"/);
    return nsMatch ? nsMatch[1] : '';
}

function extractFunctionDescription(lines, startIndex) {
    // Ищем docstring или комментарий после определения
    for (let i = startIndex + 1; i < Math.min(startIndex + 5, lines.length); i++) {
        const line = lines[i].trim();
        if (line.startsWith('"') && line.endsWith('"')) {
            return line.slice(1, -1);
        }
        if (line.startsWith(';;')) {
            return line.slice(2).trim();
        }
    }
    return '';
}

function buildTree(files) {
    const tree = {};
    
    files.forEach(file => {
        const parts = file.path.split('/');
        let current = tree;
        
        for (let i = 0; i < parts.length - 1; i++) {
            const part = parts[i];
            if (!current[part]) {
                current[part] = {
                    type: 'folder',
                    name: part,
                    children: {}
                };
            }
            current = current[part].children;
        }
        
        const fileName = parts[parts.length - 1];
        const key = fileName.replace('.cljd', '').replace(/_/g, '');
        current[key] = {
            ...file,
            children: file.children || {}
        };
    });
    
    return tree;
}

// Экспорт для использования в index.html
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { parseProject, extractFunctions };
}

