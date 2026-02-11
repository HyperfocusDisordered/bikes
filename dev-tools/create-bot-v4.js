/**
 * Create bot via BotFather - using evaluate for input (more reliable)
 */
const puppeteer = require('puppeteer');

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
  const step = process.argv[2] || 'all';
  const text = process.argv[3];

  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null,
    protocolTimeout: 120000
  });

  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));
  if (!page) { console.log('No telegram tab'); process.exit(1); }

  await page.bringToFront();
  await sleep(1000);

  // Helper: send message via DOM manipulation
  async function sendMessage(msg) {
    await page.evaluate((text) => {
      const input = document.querySelector('.input-message-input[contenteditable="true"]');
      if (!input) throw new Error('No input found');
      input.focus();
      input.innerHTML = text;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }, msg);
    await sleep(500);
    await page.keyboard.press('Enter');
    console.log('Sent:', msg);
  }

  // Helper: read last message
  async function lastMessage() {
    return await page.evaluate(() => {
      const bubbles = document.querySelectorAll('.bubble-content-wrapper');
      if (bubbles.length === 0) return '';
      return bubbles[bubbles.length - 1].innerText || '';
    });
  }

  if (step === 'send') {
    if (!text) { console.log('Usage: node create-bot-v4.js send "text"'); process.exit(1); }
    await sendMessage(text);
    await sleep(3000);
    const resp = await lastMessage();
    console.log('Response:', resp.substring(0, 300));
    process.exit(0);
  }

  if (step === 'read') {
    const msgs = await page.evaluate(() => {
      const bubbles = document.querySelectorAll('.bubble-content-wrapper');
      return Array.from(bubbles).slice(-8).map(b => b.innerText);
    });
    msgs.forEach((m, i) => console.log(`[${i}]`, m.substring(0, 200)));
    process.exit(0);
  }

  if (step === 'token') {
    const allText = await page.evaluate(() => {
      const inner = document.querySelector('.bubbles-inner');
      return inner ? inner.innerText : '';
    });
    const tokenMatch = allText.match(/(\d{10,}:[A-Za-z0-9_-]{35,})/);
    if (tokenMatch) {
      console.log('TOKEN:', tokenMatch[1]);
    } else {
      console.log('No token found');
    }
    process.exit(0);
  }

  if (step === 'screenshot') {
    await page.screenshot({ path: '/tmp/bf-current.png' });
    console.log('Screenshot: /tmp/bf-current.png');
    process.exit(0);
  }

  // step === 'all': full flow
  // BotFather already asked for name (from previous /newbot)
  const current = await lastMessage();
  console.log('Current state:', current.substring(0, 100));

  if (current.includes('call it') || current.includes('choose a name')) {
    console.log('Sending bot name...');
    await sendMessage('HyperFocus Bridge');
    await sleep(4000);
  }

  const afterName = await lastMessage();
  console.log('After name:', afterName.substring(0, 100));

  if (afterName.includes('username') || afterName.includes('must end in')) {
    console.log('Sending username...');
    await sendMessage('hyperfocus_disordered_bot');
    await sleep(5000);
  }

  await page.screenshot({ path: '/tmp/bf-final.png' });

  // Extract token
  const allText = await page.evaluate(() => {
    const inner = document.querySelector('.bubbles-inner');
    return inner ? inner.innerText : '';
  });
  const tokenMatch = allText.match(/(\d{10,}:[A-Za-z0-9_-]{35,})/);
  if (tokenMatch) {
    console.log('\n=============================');
    console.log('TOKEN:', tokenMatch[1]);
    console.log('=============================');
  } else {
    console.log('No token found. Check /tmp/bf-final.png');
  }

  process.exit(0);
}

main().catch(e => { console.error(e.message); process.exit(1); });
