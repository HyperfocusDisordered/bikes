#!/usr/bin/env node
/**
 * Переименование бота через BotFather в Telegram Web K
 * Использует Puppeteer + CDP (подключается к Arc)
 */
const puppeteer = require('puppeteer');

const NEW_USERNAME = 'KarmaRentBot';
const BOT_CURRENT = '@KarmaRentVnBot';

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function typeInChat(page, text) {
  // Telegram Web K: contenteditable div
  const input = '.input-message-input[contenteditable="true"]';
  await page.waitForSelector(input, { timeout: 10000 });
  await page.click(input);
  await sleep(300);
  // Clear existing text
  await page.keyboard.down('Meta');
  await page.keyboard.press('a');
  await page.keyboard.up('Meta');
  await page.keyboard.press('Backspace');
  await sleep(200);
  await page.type(input, text, { delay: 30 });
  await sleep(300);
  await page.keyboard.press('Enter');
  await sleep(2000);
}

async function clickCommand(page, command) {
  // Click on a command link in chat (like /mybots)
  const clicked = await page.evaluate((cmd) => {
    const links = document.querySelectorAll('.anchor-url');
    for (const link of links) {
      if (link.textContent.trim() === cmd) {
        link.click();
        return true;
      }
    }
    // Also try message text spans
    const spans = document.querySelectorAll('.text-entity-link, .anchor-url, a');
    for (const s of spans) {
      if (s.textContent.trim() === cmd) {
        s.click();
        return true;
      }
    }
    return false;
  }, command);
  if (clicked) {
    console.log(`Clicked command: ${command}`);
  } else {
    console.log(`Command ${command} not found as link, typing instead`);
    await typeInChat(page, command);
  }
  await sleep(2000);
}

async function clickInlineButton(page, text) {
  // Click inline keyboard button by text
  const clicked = await page.evaluate((btnText) => {
    const buttons = document.querySelectorAll('.reply-markup button, .keyboard-button');
    for (const btn of buttons) {
      if (btn.textContent.trim().includes(btnText)) {
        btn.click();
        return true;
      }
    }
    return false;
  }, text);
  if (clicked) {
    console.log(`Clicked button: ${text}`);
  } else {
    console.log(`Button "${text}" not found`);
  }
  await sleep(2000);
}

async function getLastMessages(page, count = 5) {
  return page.evaluate((n) => {
    const msgs = document.querySelectorAll('.message');
    const last = Array.from(msgs).slice(-n);
    return last.map(m => m.textContent.trim().substring(0, 200));
  }, count);
}

async function main() {
  console.log('Connecting to Arc via CDP...');
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  const pages = await browser.pages();
  console.log(`Found ${pages.length} tabs`);

  // Navigate to BotFather in Telegram Web K
  let page = pages.find(p => p.url().includes('web.telegram.org'));
  if (!page) {
    page = pages[0];
  }

  console.log('Navigating to BotFather...');
  await page.goto('https://web.telegram.org/k/#@BotFather', { waitUntil: 'networkidle2', timeout: 30000 });
  await sleep(5000);

  // Wait for Telegram to load and chat to open
  let title = 'unknown';
  for (let attempt = 0; attempt < 10; attempt++) {
    title = await page.evaluate(() => {
      // Try multiple selectors for chat title
      const selectors = [
        '.top .peer-title',
        '.chat-info .peer-title',
        '.sidebar-header .peer-title',
        '.topbar .peer-title',
        'a.chat-info-container .peer-title'
      ];
      for (const sel of selectors) {
        const el = document.querySelector(sel);
        if (el && el.textContent.trim()) return el.textContent.trim();
      }
      // Fallback: check all peer-title elements
      const all = document.querySelectorAll('.peer-title');
      for (const el of all) {
        if (el.textContent.includes('BotFather')) return el.textContent.trim();
      }
      return 'loading...';
    });
    console.log(`Attempt ${attempt + 1}: Chat title = "${title}"`);
    if (title.includes('BotFather')) break;
    await sleep(2000);
  }

  if (!title.includes('BotFather')) {
    // Try clicking on BotFather in the chat list
    console.log('Trying to find and click BotFather in chat list...');
    const found = await page.evaluate(() => {
      const items = document.querySelectorAll('.chatlist-chat .peer-title');
      for (const item of items) {
        if (item.textContent.includes('BotFather')) {
          item.closest('.chatlist-chat').click();
          return true;
        }
      }
      return false;
    });
    if (found) {
      console.log('Clicked BotFather in chat list');
      await sleep(3000);
    } else {
      console.error('Could not find BotFather chat! Taking screenshot for debug.');
      await page.screenshot({ path: '/tmp/botfather-debug.png' });
      return;
    }
  }

  // Step 1: Send /mybots
  console.log('\n--- Step 1: /mybots ---');
  await typeInChat(page, '/mybots');
  await sleep(3000);

  let msgs = await getLastMessages(page, 3);
  console.log('Messages:', msgs);

  // Step 2: Click on the bot button
  console.log('\n--- Step 2: Select bot ---');
  await clickInlineButton(page, 'KarmaRentVnBot');
  await sleep(2000);

  msgs = await getLastMessages(page, 3);
  console.log('Messages:', msgs);

  // Step 3: Click "Edit Bot"
  console.log('\n--- Step 3: Edit Bot ---');
  await clickInlineButton(page, 'Edit Bot');
  await sleep(2000);

  msgs = await getLastMessages(page, 3);
  console.log('Messages:', msgs);

  // Step 4: Click "Edit Botusername"
  console.log('\n--- Step 4: Edit Botusername ---');
  await clickInlineButton(page, 'Edit Botusername');
  await sleep(2000);

  msgs = await getLastMessages(page, 3);
  console.log('Messages:', msgs);

  // Step 5: Type new username
  console.log(`\n--- Step 5: Enter new username: ${NEW_USERNAME} ---`);
  await typeInChat(page, NEW_USERNAME);
  await sleep(3000);

  // Check result
  msgs = await getLastMessages(page, 5);
  console.log('\n=== RESULT ===');
  console.log('Last messages:', msgs);

  const success = msgs.some(m => m.includes('Done') || m.includes('Success') || m.includes(NEW_USERNAME));
  if (success) {
    console.log('\n✅ Bot username successfully changed!');
  } else {
    console.log('\n⚠️ Check the messages above for result');
  }

  browser.disconnect();
}

main().catch(err => {
  console.error('Error:', err.message);
  process.exit(1);
});
