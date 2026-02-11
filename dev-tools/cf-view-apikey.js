const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  const page = pages.find(p => p.url().includes('cloudflare.com/profile'));

  if (!page) {
    console.log('No Cloudflare profile page found');
    browser.disconnect();
    return;
  }

  // Click the first "View" button (Global API Key)
  const clicked = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.trim() === 'View') {
        // Check it's near "Global API Key" text
        const row = btn.closest('tr') || btn.closest('div');
        if (row && row.textContent.includes('Global API Key')) {
          btn.click();
          return 'clicked-global';
        }
      }
    }
    // Fallback: click first View
    for (const btn of btns) {
      if (btn.textContent.trim() === 'View') {
        btn.click();
        return 'clicked-first-view';
      }
    }
    return 'not-found';
  });

  console.log('View button:', clicked);
  await new Promise(r => setTimeout(r, 2000));

  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-view-key.png' });
  console.log('Screenshot saved');

  // Check if there's a modal/dialog with password or verification
  const dialogInfo = await page.evaluate(() => {
    const modals = document.querySelectorAll('[role="dialog"], .modal, [class*="modal"], [class*="Modal"]');
    const result = [];
    modals.forEach(m => {
      result.push({
        text: m.textContent.substring(0, 200),
        inputs: Array.from(m.querySelectorAll('input')).map(i => ({
          type: i.type, id: i.id, placeholder: i.placeholder
        }))
      });
    });

    // Also check for any new visible inputs (password field)
    const allInputs = document.querySelectorAll('input[type="password"], input[type="text"]');
    const passwordInputs = Array.from(allInputs).filter(i => {
      return i.type === 'password' || i.placeholder?.toLowerCase()?.includes('password');
    });
    if (passwordInputs.length > 0) {
      result.push({
        type: 'password-field',
        inputs: passwordInputs.map(i => ({ type: i.type, id: i.id, placeholder: i.placeholder }))
      });
    }

    return result;
  });

  console.log('Dialog info:', JSON.stringify(dialogInfo, null, 2));

  browser.disconnect();
}

main().catch(console.error);
