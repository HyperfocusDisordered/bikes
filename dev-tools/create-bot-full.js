/**
 * Create bot via BotFather - full flow with proper selectors
 */
const puppeteer = require('puppeteer');

const BOT_NAME = 'HyperFocus Bridge';
const BOT_USERNAME = 'hyperfocus_disordered_bot';

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('web.telegram.org'));

  if (!page) {
    page = await browser.newPage();
  }

  // Navigate to BotFather chat
  console.log('Navigating to BotFather...');
  await page.goto('https://web.telegram.org/k/#@BotFather', { waitUntil: 'networkidle2', timeout: 15000 });
  await sleep(3000);

  // Take screenshot to verify
  await page.screenshot({ path: '/tmp/bf-1-initial.png' });
  console.log('Screenshot 1: /tmp/bf-1-initial.png');

  // Check if we're in BotFather chat - look for chat header
  const inChat = await page.evaluate(() => {
    // Check multiple selectors for chat title
    const selectors = ['.top .peer-title', '.chat-info .peer-title', '.topbar .title'];
    for (const sel of selectors) {
      const el = document.querySelector(sel);
      if (el && el.textContent.includes('BotFather')) return true;
    }
    return false;
  });

  if (!inChat) {
    console.log('Not in BotFather chat. Trying to click on BotFather in chat list...');
    // Click on BotFather in the chat list
    const clicked = await page.evaluate(() => {
      const items = document.querySelectorAll('.chatlist-chat .peer-title');
      for (const item of items) {
        if (item.textContent.includes('BotFather')) {
          item.closest('.chatlist-chat').click();
          return true;
        }
      }
      return false;
    });
    if (clicked) {
      console.log('Clicked BotFather in chat list');
      await sleep(2000);
    } else {
      console.log('Could not find BotFather in chat list');
      // Try search
      const searchInput = await page.$('.input-search input');
      if (searchInput) {
        await searchInput.click();
        await searchInput.type('BotFather', { delay: 50 });
        await sleep(2000);
        await page.screenshot({ path: '/tmp/bf-search.png' });
        console.log('Screenshot: /tmp/bf-search.png');
      }
      return;
    }
  }

  await page.screenshot({ path: '/tmp/bf-2-in-chat.png' });
  console.log('Screenshot 2: /tmp/bf-2-in-chat.png');

  // Find the message input
  const inputSelector = '.input-message-input[contenteditable="true"]';
  try {
    await page.waitForSelector(inputSelector, { timeout: 5000 });
  } catch (e) {
    console.log('Message input not found');
    return;
  }

  // Send /newbot
  console.log('Step 1: Sending /newbot');
  await page.click(inputSelector);
  await sleep(200);
  await page.type(inputSelector, '/newbot', { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(4000);

  await page.screenshot({ path: '/tmp/bf-3-newbot.png' });
  console.log('Screenshot 3: /tmp/bf-3-newbot.png');

  // Send bot name
  console.log('Step 2: Sending name:', BOT_NAME);
  await page.click(inputSelector);
  await sleep(200);
  await page.type(inputSelector, BOT_NAME, { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(4000);

  await page.screenshot({ path: '/tmp/bf-4-name.png' });
  console.log('Screenshot 4: /tmp/bf-4-name.png');

  // Send bot username
  console.log('Step 3: Sending username:', BOT_USERNAME);
  await page.click(inputSelector);
  await sleep(200);
  await page.type(inputSelector, BOT_USERNAME, { delay: 30 });
  await page.keyboard.press('Enter');
  await sleep(5000);

  await page.screenshot({ path: '/tmp/bf-5-username.png' });
  console.log('Screenshot 5: /tmp/bf-5-username.png');

  // Read response and extract token
  console.log('Reading response...');
  const response = await page.evaluate(() => {
    // Try multiple selectors for messages
    const selectors = [
      '.bubble .message',
      '.bubble-content .message',
      '.bubble-content-wrapper .message',
      '.bubbles-inner .bubble'
    ];
    for (const sel of selectors) {
      const msgs = document.querySelectorAll(sel);
      if (msgs.length > 0) {
        return Array.from(msgs).slice(-5).map(m => m.innerText || m.textContent);
      }
    }
    // Fallback: get all text from bubbles area
    const bubbles = document.querySelector('.bubbles-inner');
    return bubbles ? [bubbles.innerText.slice(-2000)] : ['no messages found'];
  });

  console.log('\n=== Response ===');
  response.forEach(m => console.log(m));

  // Extract token
  const allText = response.join('\n');
  const tokenMatch = allText.match(/(\d{10,}:[A-Za-z0-9_-]{35,})/);
  if (tokenMatch) {
    console.log('\n=============================');
    console.log('BOT TOKEN:', tokenMatch[1]);
    console.log('=============================');
  } else {
    console.log('\nToken not found. Check screenshots.');
  }

  process.exit(0);
}

main().catch(e => { console.error(e); process.exit(1); });
