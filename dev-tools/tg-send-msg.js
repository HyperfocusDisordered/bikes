// Отправить сообщение в открытый чат Telegram Web
// Использование: node dev-tools/tg-send-msg.js "текст"
const puppeteer = require('puppeteer');
const text = process.argv[2];
if (!text) { console.log('Usage: node tg-send-msg.js "text"'); process.exit(1); }

(async () => {
  const b = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const tg = (await b.pages()).find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }

  // Clear input
  await tg.evaluate(() => {
    const inp = document.querySelector('.input-message-input[contenteditable="true"]');
    if (inp) { inp.textContent = ''; inp.focus(); }
  });
  await new Promise(r => setTimeout(r, 200));

  // Type text
  await tg.evaluate((t) => {
    const inp = document.querySelector('.input-message-input[contenteditable="true"]');
    if (inp) {
      inp.textContent = t;
      inp.dispatchEvent(new Event('input', {bubbles: true}));
    }
  }, text);
  await new Promise(r => setTimeout(r, 500));

  // Click send button
  const result = await tg.evaluate(() => {
    const btn = document.querySelector('.btn-send-container .btn-icon');
    if (btn) { btn.click(); return 'sent via btn-icon'; }
    const btn2 = document.querySelector('.btn-send');
    if (btn2) { btn2.click(); return 'sent via btn-send'; }
    return 'no send button';
  });
  console.log(result);
  process.exit(0);
})().catch(e => { console.error(e.message); process.exit(1); });
