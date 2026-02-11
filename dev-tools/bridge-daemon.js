#!/usr/bin/env node
/**
 * HyperFocus Bridge Daemon — мост между Telegram и Claude Code
 *
 * Поллит API на новые сообщения от Denis (@dovchar)
 * Выводит их в stdout (Claude Code видит)
 * Принимает команды через файл ~/bikes/dev-tools/.bridge-outbox
 *
 * Автозапуск: LaunchAgent (см. install-daemon.sh)
 */

const https = require('https');
const fs = require('fs');
const path = require('path');
const { execSync, exec } = require('child_process');
const os = require('os');

const API_BASE = 'https://karmarent.app/api/admin';
const TOKEN = 'kr-admin-2026';
const HF_BOT_TOKEN = '8300954318:AAGT5lYHVCJf_RVw3fUs-GMe2NB6-Y_6Nxw';
const CLAUDE_CMD = '/opt/homebrew/bin/claude';
const WHISPER_CMD = path.join(__dirname, '.whisper-venv/bin/whisper');
const POLL_INTERVAL = 3000; // 3 секунды
const OUTBOX_FILE = path.join(__dirname, '.bridge-outbox');
const INBOX_FILE = path.join(__dirname, '.bridge-inbox');
const LOG_FILE = path.join(__dirname, '.bridge-log');

// ── Project Registry ─────────────────────────────
// Known projects with aliases (auto-discovered projects added below)
const PROJECTS = {
  bikes:    { dir: `${os.homedir()}/bikes`,            name: 'Karma Rent (bikes)' },
  tapyou:   { dir: `${os.homedir()}/mobile_app_ios`,   name: 'TapYou iOS' },
  afloatx:  { dir: `${os.homedir()}/AfloatX`,          name: 'ClipboardX (AfloatX)' },
};

// Auto-discover projects from ~/.claude/projects/
// Directory names encode paths: -Users-denisovchar-bikes → ~/bikes
(function discoverProjects() {
  try {
    const claudeProjectsDir = path.join(os.homedir(), '.claude', 'projects');
    const entries = fs.readdirSync(claudeProjectsDir);
    const home = os.homedir();
    for (const entry of entries) {
      // Decode: -Users-denisovchar-bikes → /Users/denisovchar/bikes
      const projectPath = '/' + entry.replace(/-/g, '/');
      // Skip if not under home dir or doesn't exist
      if (!projectPath.startsWith(home) || !fs.existsSync(projectPath)) continue;
      // Derive short name from last path component (lowercase)
      const shortName = path.basename(projectPath).toLowerCase().replace(/[^a-z0-9]/g, '');
      if (!shortName || PROJECTS[shortName]) continue; // already registered
      PROJECTS[shortName] = { dir: projectPath, name: path.basename(projectPath), discovered: true };
    }
  } catch (e) { /* ignore — ~/.claude/projects/ may not exist */ }
})();

let currentProject = 'bikes'; // default

// Detect project switch from natural language
// Returns project name or null
function detectProjectSwitch(text) {
  const lower = text.toLowerCase().trim();
  // Patterns: "переключи на X", "открой X", "давай X", "/project X", "project X", "на X переключи"
  // \w doesn't match Cyrillic — use [а-яёa-z0-9]+ for project names
  const W = '[а-яёa-z0-9]+';
  const patterns = [
    new RegExp(`(?:переключи(?:сь)?|перейди|открой|давай|запусти|стартуй|включи|go to|switch to|switch)\\s+(?:на\\s+)?(${W})`, 'i'),
    new RegExp(`(?:на\\s+)(${W})\\s+(?:переключи|перейди|давай)`, 'i'),
    new RegExp(`^\\/?project\\s+(${W})$`, 'i'),
  ];
  for (const pat of patterns) {
    const m = lower.match(pat);
    if (m) {
      const name = m[1];
      // Check if it's a known project name (or alias)
      if (PROJECTS[name]) return name;
      // Aliases
      const aliases = {
        'байки': 'bikes', 'байкс': 'bikes', 'карма': 'bikes', 'карму': 'bikes', 'рент': 'bikes',
        'тапю': 'tapyou', 'тапъю': 'tapyou', 'тап': 'tapyou', 'tap': 'tapyou', 'ios': 'tapyou',
        'афлоат': 'afloatx', 'клипборд': 'afloatx', 'clipboard': 'afloatx', 'буферфлай': 'afloatx', 'bufferfly': 'afloatx', 'clipboardx': 'afloatx',
      };
      if (aliases[name]) return aliases[name];
    }
  }
  return null;
}

// Dedup: track recently processed messages to avoid duplicates (Telegram webhook retries)
const recentMessages = new Set();
function isDuplicate(text, ts) {
  const key = `${text}|${ts}`;
  if (recentMessages.has(key)) return true;
  recentMessages.add(key);
  // Clean old entries after 60s
  setTimeout(() => recentMessages.delete(key), 60000);
  return false;
}

// ── Auto-start Claude in tmux ────────────────────────
let claudeStarting = false;

function isClaudeRunning() {
  try {
    execSync(`/opt/homebrew/bin/tmux has-session -t claude 2>/dev/null`);
    return true;
  } catch (e) {
    return false;
  }
}

function hasAttachedClient() {
  try {
    const clients = execSync(`/opt/homebrew/bin/tmux list-clients -t claude 2>/dev/null`).toString().trim();
    return clients.length > 0;
  } catch (e) {
    return false;
  }
}

let terminalOpened = false; // Cooldown flag

function openTerminalWithClaude() {
  if (terminalOpened) return;
  terminalOpened = true;
  setTimeout(() => { terminalOpened = false; }, 30000); // 30s cooldown

  log('🖥️ Opening Warp tab (zshrc auto-attaches to tmux)...');

  // Activate Warp + Cmd+T → new tab → .zshrc detects tmux claude → auto-attach
  // NO explicit keystroke typing — .zshrc handles everything with absolute paths
  exec(`open -a Warp`, () => {
    setTimeout(() => {
      exec(`osascript -e 'tell application "System Events"' -e 'tell process "Warp"' -e 'keystroke "t" using command down' -e 'end tell' -e 'end tell'`, (err) => {
        if (err) {
          log(`⚠️ Warp Cmd+T failed: ${err.message}`);
        } else {
          log('✅ Warp tab opened — zshrc will auto-attach to tmux');
        }
      });
    }, 1500);
  });
}

function killAllClaude() {
  // Only kill Claude processes INSIDE tmux session, not ones in regular terminals
  try {
    const tmuxPanePid = execSync(`/opt/homebrew/bin/tmux list-panes -t claude -F '#{pane_pid}' 2>/dev/null`).toString().trim();
    if (tmuxPanePid) {
      // Kill the process tree inside the tmux pane
      execSync(`pkill -P ${tmuxPanePid} 2>/dev/null || true`);
    }
  } catch (_) {}
  try { execSync(`/opt/homebrew/bin/tmux kill-session -t claude 2>/dev/null`); } catch (_) {}
  log('🧹 Killed tmux claude session (non-tmux Claude processes left intact)');
}

function startClaude(projectName) {
  if (claudeStarting) return;
  claudeStarting = true;
  const proj = projectName || currentProject;
  const projConfig = PROJECTS[proj];
  if (!projConfig) {
    log(`❌ Unknown project: ${proj}`);
    claudeStarting = false;
    return;
  }
  currentProject = proj;
  const projDir = projConfig.dir;
  log(`🚀 Starting Claude CLI (project: ${proj}, dir: ${projDir})...`);
  try {
    // 1. Kill ALL old Claude sessions first
    killAllClaude();

    // 2. Wait for processes to die
    execSync('sleep 2');

    // 3. Regenerate boot.md for this project
    try {
      execSync(`${os.homedir()}/meta/scripts/generate-boot.sh ${proj}`);
      log(`✅ boot.md regenerated for [${proj}]`);
    } catch (e) { log('⚠️ boot.md generation failed: ' + e.message); }

    // 4. Create tmux session with Claude (auto-restart loop)
    // PATH must be exported INSIDE the shell command — tmux -e sets session env but /bin/sh doesn't inherit it
    // Auto-restart loop: if Claude exits, wait 5s and restart (no `read` — works headless)
    execSync(`/opt/homebrew/bin/tmux new-session -d -s claude "export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; cd ${projDir} && while true; do ${CLAUDE_CMD} --dangerously-skip-permissions; echo '⚡ Claude exited, restarting in 5s...'; sleep 5; done"`);
    log(`✅ Claude tmux session created for [${proj}] (auto-restart loop)`);

    // 5. Open Warp tab attached to tmux after Claude initializes
    setTimeout(() => openTerminalWithClaude(), 3000);

    // 6. Wait for Claude to initialize before allowing nudges
    setTimeout(() => {
      claudeStarting = false;
      log('✅ Claude should be ready now');
    }, 12000);
  } catch (e) {
    claudeStarting = false;
    log(`❌ Failed to start Claude: ${e.message}`);
  }
}

async function switchProject(projectName) {
  const proj = projectName.toLowerCase().trim();
  if (!PROJECTS[proj]) {
    const available = Object.keys(PROJECTS).map(k => `  ${k} — ${PROJECTS[k].name}`).join('\n');
    await apiRequest('POST', 'owner-send', {
      text: `❌ Неизвестный проект: ${projectName}\n\n<pre>Доступные:\n${available}</pre>`
    });
    return;
  }
  if (proj === currentProject) {
    await apiRequest('POST', 'owner-send', {
      text: `ℹ️ Уже на проекте ${proj} (${PROJECTS[proj].name})`
    });
    return;
  }

  log(`🔄 SWITCH PROJECT: ${currentProject} → ${proj}`);
  await apiRequest('POST', 'owner-send', {
    text: `🔄 Переключаюсь: ${currentProject} → ${proj} (${PROJECTS[proj].name})...`
  });

  // IMPORTANT: Set currentProject and claudeStarting BEFORE killing
  // to prevent ensureClaude() race condition during the wait
  currentProject = proj;
  claudeStarting = true;

  // Kill current Claude session
  killAllClaude();
  await new Promise(r => setTimeout(r, 3000));

  // Start new session in new project dir
  claudeStarting = false; // Reset so startClaude can proceed
  startClaude(proj);

  await new Promise(r => setTimeout(r, 2000));
  await apiRequest('POST', 'owner-send', {
    text: `✅ Переключён на ${proj} (${PROJECTS[proj].name})\nClaude стартует в ${PROJECTS[proj].dir}`
  });
}

function ensureClaude() {
  if (!isClaudeRunning()) {
    startClaude();
    return false; // not ready yet
  }
  return true;
}

// Debounce nudge — don't spam terminal if multiple messages arrive at once
let lastNudge = 0;
let lastInboxMtime = 0; // track inbox changes to avoid nudge spam

// Detect Claude running in a regular terminal (not tmux)
function findClaudeTTY() {
  try {
    // Find claude processes on real ttys (s0XX), exclude tmux and daemon
    const ps = execSync(`ps -eo pid,tty,comm | grep claude | grep -v tmux | grep -v grep | grep -v bridge`).toString().trim();
    for (const line of ps.split('\n')) {
      const match = line.trim().match(/^\d+\s+(s\d+)\s+claude/);
      if (match) return match[1]; // e.g. "s001"
    }
  } catch (e) {}
  return null;
}

function nudgeClaude() {
  if (claudeStarting) return; // don't nudge during startup
  const now = Date.now();
  if (now - lastNudge < 10000) return; // max once per 10 sec (gives Claude time to process)
  lastNudge = now;

  // Strategy 1: Try tmux nudge
  if (isClaudeRunning()) {
    try {
      const pane = execSync(`/opt/homebrew/bin/tmux capture-pane -t claude -p 2>/dev/null`).toString();
      // Check only last 5 lines for idle prompt — avoids false negatives
      // from "thinking"/"interrupt" in old output scrolled above
      const lines = pane.trimEnd().split('\n');
      const tail = lines.slice(-5).join('\n');
      if (tail.includes('❯')) {
        execSync(`/opt/homebrew/bin/tmux send-keys -t claude "check telegram"`);
        execSync(`/opt/homebrew/bin/tmux send-keys -t claude C-m`);
        log('⚡ NUDGE: sent "check telegram" to tmux session');
        return;
      }
    } catch (e) {}
    // tmux exists but Claude not idle — skip Strategy 2 to avoid cross-match
    log('⏳ SKIP NUDGE: tmux alive but no prompt in last 5 lines');
    return;
  }

  // Strategy 2: Claude in regular terminal (Warp) — only when no tmux session
  const tty = findClaudeTTY();
  if (tty) {
    try {
      // Activate Warp and type "check telegram" + Enter
      exec(`osascript -e '
        tell application "Warp" to activate
        delay 0.5
        tell application "System Events"
          keystroke "check telegram"
          delay 0.1
          key code 36
        end tell
      '`, (err) => {
        if (err) log(`⚠️ Warp nudge failed: ${err.message}`);
      });
      log(`⚡ NUDGE: sent "check telegram" to Warp (tty: ${tty})`);
      return;
    } catch (e) {}
  }

  log('⏳ SKIP NUDGE: no idle Claude found');
}

function log(msg) {
  const ts = new Date().toLocaleTimeString('en-US', { hour12: false });
  const line = `[${ts}] ${msg}`;
  console.log(line);
  // Also append to log file
  fs.appendFileSync(LOG_FILE, line + '\n');
}

function apiRequest(method, endpoint, body) {
  return new Promise((resolve, reject) => {
    const url = new URL(`${API_BASE}/${endpoint}`);
    const options = {
      hostname: url.hostname,
      path: url.pathname,
      method: method,
      headers: {
        'Authorization': `Bearer ${TOKEN}`,
        'Content-Type': 'application/json'
      }
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          resolve({ raw: data });
        }
      });
    });

    req.on('error', (e) => reject(e));

    if (body) {
      req.write(JSON.stringify(body));
    }
    req.end();
  });
}

// ── Voice transcription ──────────────────────────────

function telegramApiRequest(method) {
  return new Promise((resolve, reject) => {
    const url = new URL(`https://api.telegram.org/bot${HF_BOT_TOKEN}/${method}`);
    https.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { reject(new Error('JSON parse error')); }
      });
    }).on('error', reject);
  });
}

function downloadFile(filePath) {
  return new Promise((resolve, reject) => {
    const url = new URL(`https://api.telegram.org/file/bot${HF_BOT_TOKEN}/${filePath}`);
    https.get(url, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    }).on('error', reject);
  });
}

async function transcribeVoice(fileId) {
  try {
    // 1. Get file path from Telegram
    const fileInfo = await telegramApiRequest(`getFile?file_id=${fileId}`);
    if (!fileInfo.ok || !fileInfo.result?.file_path) {
      log(`❌ Voice: getFile failed for ${fileId}`);
      return null;
    }

    // 2. Download audio file
    const audioBuffer = await downloadFile(fileInfo.result.file_path);
    const tmpFile = path.join(os.tmpdir(), `voice_${Date.now()}.ogg`);
    fs.writeFileSync(tmpFile, audioBuffer);
    log(`🎤 Voice: downloaded ${audioBuffer.length} bytes → ${tmpFile}`);

    // 3. Run whisper locally (need /opt/homebrew/bin in PATH for ffmpeg)
    const outputDir = os.tmpdir();
    const env = { ...process.env, PATH: `/opt/homebrew/bin:${process.env.PATH || '/usr/bin:/bin'}` };
    try {
      const whisperOutput = execSync(
        `${WHISPER_CMD} "${tmpFile}" --language ru --model tiny --output_format txt --output_dir "${outputDir}"`,
        { timeout: 120000, stdio: 'pipe', env }
      ).toString();
      log(`🎤 Whisper output: ${whisperOutput.trim().substring(0, 100)}`);
    } catch (e) {
      log(`❌ Voice: whisper failed: ${e.stderr?.toString() || e.message}`);
      // Clean up
      try { fs.unlinkSync(tmpFile); } catch (_) {}
      return null;
    }

    // 4. Read transcription
    const txtFile = tmpFile.replace('.ogg', '.txt');
    let transcript = '';
    try {
      transcript = fs.readFileSync(txtFile, 'utf-8').trim();
    } catch (e) {
      log(`❌ Voice: can't read transcript file ${txtFile}`);
      return null;
    }

    // 5. Clean up temp files
    try { fs.unlinkSync(tmpFile); } catch (_) {}
    try { fs.unlinkSync(txtFile); } catch (_) {}

    log(`📝 Voice transcribed: "${transcript.substring(0, 80)}${transcript.length > 80 ? '...' : ''}"`);
    return transcript;
  } catch (e) {
    log(`❌ Voice transcription error: ${e.message}`);
    return null;
  }
}

async function handleVoiceMessage(text, ts) {
  const match = text.match(/^\[voice:(.+)\]$/);
  if (!match) return false;

  const fileId = match[1];
  log(`🎤 Voice message detected, file_id: ${fileId}`);

  const transcript = await transcribeVoice(fileId);

  if (transcript) {
    // Write transcription to inbox (Claude will see it)
    fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: 🎤 ${transcript}\n`);
    // Send transcription back to Telegram
    await apiRequest('POST', 'owner-send', {
      text: `📝 <b>Транскрипция:</b>\n\n${transcript}`
    });
    nudgeClaude();
  } else {
    // Transcription failed — write raw marker to inbox
    fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: [не удалось транскрибировать голосовое]\n`);
    await apiRequest('POST', 'owner-send', {
      text: '❌ Не удалось транскрибировать голосовое сообщение'
    });
    nudgeClaude();
  }

  return true;
}

// ── Photo handling ───────────────────────────────────

const PHOTOS_DIR = path.join(__dirname, '.bridge-photos');
if (!fs.existsSync(PHOTOS_DIR)) fs.mkdirSync(PHOTOS_DIR, { recursive: true });

async function handlePhotoMessage(text, ts) {
  const match = text.match(/^\[photo:([^|\]]+)(?:\|(.+))?\]$/);
  if (!match) return false;

  const fileId = match[1];
  const caption = match[2] || '';
  log(`📷 Photo message detected, file_id: ${fileId}${caption ? `, caption: ${caption}` : ''}`);

  try {
    // 1. Get file path from Telegram
    const fileInfo = await telegramApiRequest(`getFile?file_id=${fileId}`);
    if (!fileInfo.ok || !fileInfo.result?.file_path) {
      log(`❌ Photo: getFile failed for ${fileId}`);
      fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: [не удалось скачать фото]\n`);
      nudgeClaude();
      return true;
    }

    // 2. Download photo
    const photoBuffer = await downloadFile(fileInfo.result.file_path);
    const ext = path.extname(fileInfo.result.file_path) || '.jpg';
    const photoFile = path.join(PHOTOS_DIR, `photo_${Date.now()}${ext}`);
    fs.writeFileSync(photoFile, photoBuffer);
    log(`📷 Photo: downloaded ${photoBuffer.length} bytes → ${photoFile}`);

    // 3. Write to inbox with local path (Claude can Read the image)
    const captionText = caption ? ` (подпись: ${caption})` : '';
    fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: 📷 Фото${captionText}: ${photoFile}\n`);
    nudgeClaude();
  } catch (e) {
    log(`❌ Photo error: ${e.message}`);
    fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: [ошибка при скачивании фото]\n`);
    nudgeClaude();
  }

  return true;
}

async function checkMessages() {
  try {
    const result = await apiRequest('GET', 'owner-msgs');
    const messages = result?.data?.messages || [];

    if (messages.length === 0) return;

    // Write ALL to inbox FIRST (append-only, never lose)
    for (const msg of messages) {
      const ts = msg.created_at || '';
      const text = msg.text || '';
      // Dedup check (Telegram may retry webhooks)
      if (isDuplicate(text, ts)) {
        log(`⏭️ SKIP DUPLICATE: ${text.substring(0, 40)}`);
        continue;
      }

      log(`📨 DENIS: ${text} [${ts}]`);

      // Handle permission callbacks from Telegram buttons
      const permMatch = text.match(/^\[btn\] perm_(allow|deny|always)_(perm-\d+)$/);
      if (permMatch) {
        const decision = permMatch[1]; // 'allow', 'deny', or 'always'
        const reqId = permMatch[2];    // 'perm-1234567890'
        const approvalFile = `/tmp/claude-permissions/${reqId}`;
        try {
          fs.writeFileSync(approvalFile, decision);
          log(`🔑 PERMISSION ${decision.toUpperCase()}: ${reqId}`);
        } catch (e) {
          log(`❌ Permission write error: ${e.message}`);
        }
      } else if (text.toLowerCase() === 'restart claude' || text.toLowerCase() === '/restart') {
        // Restart Claude CLI in tmux with --dangerously-skip-permissions
        log('🔄 RESTART CLAUDE requested by Denis');
        try {
          try { execSync(`/opt/homebrew/bin/tmux kill-session -t claude 2>/dev/null`); } catch (_) {}
          await new Promise(r => setTimeout(r, 2000));
          startClaude(currentProject);
          log('✅ Claude restarted in tmux (auto-restart loop)');
          await apiRequest('POST', 'owner-send', {
            text: `✅ Claude перезапущен (проект: ${currentProject})`
          });
        } catch (e) {
          log(`❌ Restart failed: ${e.message}`);
          await apiRequest('POST', 'owner-send', {
            text: `❌ Ошибка перезапуска: ${e.message}`
          });
        }
        // Daemon-level command — don't write to inbox
      } else if (detectProjectSwitch(text)) {
        // Natural language project switch
        const projName = detectProjectSwitch(text);
        await switchProject(projName);
        // Daemon-level command — don't write to inbox
      } else if (text.toLowerCase() === '/project' || text.toLowerCase() === '/projects' || text.toLowerCase() === 'projects' || text.match(/какие проекты/i) || text.match(/список проектов/i)) {
        // List available projects
        const list = Object.entries(PROJECTS).map(([k, v]) =>
          `${k === currentProject ? '👉 ' : '   '}${k} — ${v.name}${v.discovered ? ' (auto)' : ''}`
        ).join('\n');
        await apiRequest('POST', 'owner-send', {
          text: `<pre>Проекты:\n${list}\n\nПереключить: переключи на имя</pre>`
        });
        // Daemon-level command — don't write to inbox
      } else if (text.toLowerCase() === 'diffs on' || text.toLowerCase() === 'diffs off') {
        // Toggle diff mode
        const enabled = text.toLowerCase() === 'diffs on';
        const flagFile = path.join(__dirname, '.bridge-diffs-enabled');
        if (enabled) {
          fs.writeFileSync(flagFile, '1');
          log('⚙️ Diffs mode: ON');
        } else {
          try { fs.unlinkSync(flagFile); } catch (_) {}
          log('⚙️ Diffs mode: OFF');
        }
        await apiRequest('POST', 'owner-send', {
          text: enabled ? '✅ Режим дифов ВКЛЮЧЁН — буду показывать git changes' : '❌ Режим дифов ВЫКЛЮЧЕН'
        });
        // Don't write to inbox, don't nudge Claude — this is a daemon-level command
      } else if (text.match(/^\[voice:.+\]$/)) {
        // Voice message — transcribe locally
        await handleVoiceMessage(text, ts);
      } else if (text.match(/^\[photo:.+\]$/)) {
        // Photo message — download locally
        await handlePhotoMessage(text, ts);
      } else {
        // Regular message — write to inbox (Claude reads via Stop hook)
        fs.appendFileSync(INBOX_FILE, `DENIS [${ts}]: ${text}\n`);
        // Wake up Claude Code by typing into terminal
        nudgeClaude();
      }
    }

    // THEN ack (mark read) — only after inbox write succeeded
    await apiRequest('POST', 'owner-ack', {});
  } catch (e) {
    // Тихо — сервер может быть в sleep
  }
}

async function processOutbox() {
  try {
    if (!fs.existsSync(OUTBOX_FILE)) return;

    const content = fs.readFileSync(OUTBOX_FILE, 'utf-8').trim();
    if (!content) return;

    // Clear outbox immediately
    fs.writeFileSync(OUTBOX_FILE, '');

    const lines = content.split('\n');
    for (const line of lines) {
      if (!line.trim()) continue;

      try {
        const parsed = JSON.parse(line);
        await apiRequest('POST', 'owner-send', parsed);
        log(`📤 SENT: ${parsed.text.substring(0, 80)}${parsed.text.length > 80 ? '...' : ''}`);
      } catch (e) {
        await apiRequest('POST', 'owner-send', { text: line });
        log(`📤 SENT: ${line.substring(0, 80)}${line.length > 80 ? '...' : ''}`);
      }
    }
  } catch (e) {
    // Ignore outbox errors
  }
}

function retryNudgeIfNeeded() {
  // Only nudge if inbox has NEW content since last nudge (mtime changed)
  try {
    if (!fs.existsSync(INBOX_FILE)) return;
    const stat = fs.statSync(INBOX_FILE);
    const mtime = stat.mtimeMs;
    if (mtime <= lastInboxMtime) return; // no new content — skip
    if (stat.size === 0) return; // empty file — skip
    lastInboxMtime = mtime;
    nudgeClaude();
  } catch (e) {}
}

const RESTART_SIGNAL = path.join(__dirname, '.bridge-restart');

async function checkRestartSignal() {
  try {
    if (!fs.existsSync(RESTART_SIGNAL)) return;
    const flags = fs.readFileSync(RESTART_SIGNAL, 'utf-8').trim();
    fs.unlinkSync(RESTART_SIGNAL);
    log('🔄 RESTART SIGNAL detected — killing Claude processes...');

    // Kill all Claude CLI processes (not Claude.app — it uses capital C)
    try { execSync('pkill -x claude 2>/dev/null || true'); } catch (_) {}
    try { execSync(`/opt/homebrew/bin/tmux kill-session -t claude 2>/dev/null`); } catch (_) {}

    // Wait for processes to die
    await new Promise(r => setTimeout(r, 3000));

    // Start new tmux session — uses currentProject dir
    startClaude(currentProject);
    log(`✅ Claude restarted in tmux (project: ${currentProject})`);

    await apiRequest('POST', 'owner-send', {
      text: `✅ Claude перезапущен${skipPerms ? ' с авторазрешениями' : ''} — вкладка Warp открывается`
    });

    claudeStarting = true;
    setTimeout(() => { claudeStarting = false; }, 10000);
  } catch (e) {
    log(`❌ Restart signal error: ${e.message}`);
  }
}

async function poll() {
  ensureClaude(); // Always check if Claude is alive, restart + open Warp if not
  // If tmux alive but no terminal attached — open Warp tab (30s cooldown inside)
  if (isClaudeRunning() && !hasAttachedClient() && !claudeStarting) {
    openTerminalWithClaude();
  }
  await checkRestartSignal();
  await checkMessages();
  await processOutbox();
  retryNudgeIfNeeded();
}

// Main loop
log('🚀 HyperFocus Bridge Daemon started');
log(`   Polling every ${POLL_INTERVAL/1000}s`);
log(`   Outbox: ${OUTBOX_FILE}`);
log(`   Log: ${LOG_FILE}`);

// Ensure Claude is running on daemon start
ensureClaude();

// Initial poll (after short delay to let Claude start if needed)
setTimeout(poll, 3000);

// Regular polling
setInterval(poll, POLL_INTERVAL);

// Keep alive
process.on('SIGINT', () => {
  log('Bridge daemon stopped');
  process.exit(0);
});
