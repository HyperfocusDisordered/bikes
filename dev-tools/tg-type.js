// Ввести текст и отправить в текущий чат Telegram Web K
// Использует evaluate вместо click/type (надёжнее)
const puppeteer = require('puppeteer');
const text = process.argv[2] || '/start';

(async () => {
  const b = await puppeteer.connect({browserURL: 'http://localhost:9222', defaultViewport: null});
  const tg = (await b.pages()).find(p => p.url().includes('telegram'));
  if (!tg) { console.log('No TG tab'); process.exit(1); }

  const result = await tg.evaluate((msg) => {
    const input = document.querySelector('.input-message-input[contenteditable="true"]');
    if (!input) return 'no input field';
    input.focus();
    input.textContent = msg;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    return 'typed: ' + msg;
  }, text);
  console.log(result);

  // Нажать Send
  await new Promise(r => setTimeout(r, 300));
  const sent = await tg.evaluate(() => {
    const sendBtn = document.querySelector('.btn-send-container .send-btn') ||
                    document.querySelector('button.send');
    if (sendBtn) { sendBtn.click(); return 'sent via button'; }
    // Или Enter
    const input = document.querySelector('.input-message-input');
    if (input) {
      input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', bubbles: true}));
      return 'sent via Enter';
    }
    return 'cannot send';
  });
  console.log(sent);
  process.exit(0);
})().catch(e => { console.error(e.message); process.exit(1); });
