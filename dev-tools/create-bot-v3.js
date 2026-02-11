/**
 * Create bot via BotFather - with bringToFront and increased timeout
 */
const puppeteer = require('puppeteer');

const BOT_NAME = 'HyperFocus Bridge';
const BOT_USERNAME = 'hyperfocus_disordered_bot';

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function sendMsg(page, text) {
  const sel = '.input-message-input[contenteditable="true"]';
  await page.click(sel);
  await sleep(300);
  // Clear any existing text first
  await page.keyboard.down('Meta');
  await page.keyboard.press('a');
  await page.keyboard.up('Meta');
  await page.keyboard.press('Backspace');
  await sleep(200);
  await page.type(sel, text, { delay: 40 });
  await sleep(300);
  await page.keyboard.press('Enter');
  console.log('  Sent:', text);
}

async function readLastMsg(page) {
  // Use innerText from bubbles to get last messages
  return await page.evaluate(() => {
    const bubbles = document.querySelectorAll('.bubble-content-wrapper');
    if (bubbles.length === 0) return 'no bubbles';
    const last = bubbles[bubbles.length - 1];
    return last.innerText || last.textContent || 'empty';
  });
}

async function main() {
  console.log('Connecting to Arc with increased timeout...');
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null,
    protocolTimeout: 60000
  });

  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    page = await browser.newPage();
  }

  // CRITICAL: bring tab to front
  await page.bringToFront();
  console.log('Tab activated');

  // Navigate to BotFather
  console.log('Going to BotFather...');
  await page.goto('https://web.telegram.org/k/#@BotFather', { waitUntil: 'domcontentloaded', timeout: 15000 });
  await sleep(4000);

  // Wait for message input
  const sel = '.input-message-input[contenteditable="true"]';
  try {
    await page.waitForSelector(sel, { timeout: 10000 });
    console.log('Input ready');
  } catch (e) {
    console.log('Input not found, aborting');
    await page.screenshot({ path: '/tmp/bf-fail.png' });
    process.exit(1);
  }

  // Check current state - maybe /newbot was already sent
  const lastMsg = await readLastMsg(page);
  console.log('Last message:', lastMsg.substring(0, 100));

  if (lastMsg.includes('Alright, a new bot')) {
    // BotFather is asking for name
    console.log('BotFather waiting for name, sending...');
    await sendMsg(page, BOT_NAME);
    await sleep(3000);
  } else if (lastMsg.includes('choose a username')) {
    // BotFather is asking for username
    console.log('BotFather waiting for username, sending...');
    await sendMsg(page, BOT_USERNAME);
    await sleep(4000);
  } else if (lastMsg.includes('Done! Congratulations')) {
    console.log('Bot already created!');
  } else {
    // Start from scratch
    console.log('Starting /newbot flow...');
    await sendMsg(page, '/newbot');
    await sleep(3000);

    const r1 = await readLastMsg(page);
    console.log('After /newbot:', r1.substring(0, 100));

    if (r1.includes('choose a name') || r1.includes('call it')) {
      await sendMsg(page, BOT_NAME);
      await sleep(3000);

      const r2 = await readLastMsg(page);
      console.log('After name:', r2.substring(0, 100));

      if (r2.includes('choose a username') || r2.includes('must end in')) {
        await sendMsg(page, BOT_USERNAME);
        await sleep(4000);
      }
    }
  }

  // Final screenshot and read
  await page.screenshot({ path: '/tmp/bf-final.png' });
  console.log('Final screenshot: /tmp/bf-final.png');

  // Try to extract token from all visible text
  const allText = await page.evaluate(() => {
    const inner = document.querySelector('.bubbles-inner');
    return inner ? inner.innerText : '';
  });

  const tokenMatch = allText.match(/(\d{10,}:[A-Za-z0-9_-]{35,})/);
  if (tokenMatch) {
    console.log('\n=============================');
    console.log('BOT TOKEN:', tokenMatch[1]);
    console.log('=============================');
  } else {
    // Check in last few messages specifically
    const msgs = await page.evaluate(() => {
      const bubbles = document.querySelectorAll('.bubble-content-wrapper');
      return Array.from(bubbles).slice(-5).map(b => b.innerText);
    });
    console.log('\nLast 5 messages:');
    msgs.forEach((m, i) => console.log(`[${i}]`, m.substring(0, 200)));

    const tokenMatch2 = msgs.join('\n').match(/(\d{10,}:[A-Za-z0-9_-]{35,})/);
    if (tokenMatch2) {
      console.log('\n=============================');
      console.log('BOT TOKEN:', tokenMatch2[1]);
      console.log('=============================');
    } else {
      console.log('Token not found. Check /tmp/bf-final.png');
    }
  }

  process.exit(0);
}

main().catch(e => { console.error(e.message); process.exit(1); });
