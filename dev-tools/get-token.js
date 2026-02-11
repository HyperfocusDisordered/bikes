const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  // Find the Telegram tab
  const pages = await browser.pages();
  let tgPage = null;
  for (const p of pages) {
    const url = await p.url();
    if (url.includes('web.telegram.org')) {
      tgPage = p;
      break;
    }
  }

  if (!tgPage) {
    console.log('No Telegram tab found');
    browser.disconnect();
    return;
  }

  console.log('Found Telegram tab');

  // Extract all text from the chat to find the token
  const result = await tgPage.evaluate(() => {
    // Get all message text
    const bubbles = document.querySelectorAll('.bubble .message, .bubble .text-content, .bubble');
    const texts = [];
    bubbles.forEach(b => {
      const text = b.innerText || b.textContent;
      if (text && text.length > 5) {
        texts.push(text.trim());
      }
    });
    return texts.join('\n===\n');
  });

  // Find token pattern (number:alphanumeric string)
  const tokenRegex = /(\d{8,}:[A-Za-z0-9_-]{30,})/;
  const match = result.match(tokenRegex);

  if (match) {
    console.log('TOKEN: ' + match[1]);
  } else {
    console.log('Token not found. Searching in raw text...');
    // Print last part of text
    const lines = result.split('\n');
    const relevant = lines.filter(l => l.includes('token') || l.includes('HTTP') || l.includes(':'));
    console.log('Relevant lines:', relevant.slice(-10).join('\n'));
  }

  browser.disconnect();
}

main().catch(console.error);
