const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });

  // Open Gmail and look for Cloudflare email
  const page = await browser.newPage();
  await page.goto('https://mail.google.com/mail/u/0/#search/from:cloudflare+newer_than:1h', {
    waitUntil: 'networkidle2',
    timeout: 30000
  });
  await new Promise(r => setTimeout(r, 5000));

  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/gmail-cf.png' });
  console.log('Gmail screenshot saved');

  // Try to find and click the latest Cloudflare email
  const emailClicked = await page.evaluate(() => {
    const rows = document.querySelectorAll('tr');
    for (const row of rows) {
      if (row.textContent.includes('Cloudflare') || row.textContent.includes('verification') || row.textContent.includes('code')) {
        row.click();
        return row.textContent.substring(0, 100);
      }
    }
    return 'not found';
  });
  console.log('Email found:', emailClicked);

  await new Promise(r => setTimeout(r, 3000));
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/gmail-cf-email.png' });

  // Extract the 7-digit code from email body
  const code = await page.evaluate(() => {
    const body = document.body.innerText;
    // Look for 7 digit code
    const matches = body.match(/\b(\d{7})\b/g);
    return { matches, bodySnippet: body.substring(0, 1000) };
  });
  console.log('Code search:', JSON.stringify(code?.matches));

  browser.disconnect();
}

main().catch(console.error);
