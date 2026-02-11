const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('cloudflare.com'));

  if (!page) {
    console.log('No Cloudflare page found');
    browser.disconnect();
    return;
  }

  // List ALL inputs on the page
  const inputs = await page.$$('input');
  console.log(`Total inputs found: ${inputs.length}`);

  for (let i = 0; i < inputs.length; i++) {
    const info = await page.evaluate(el => ({
      tag: el.tagName,
      type: el.type,
      id: el.id,
      name: el.name,
      placeholder: el.placeholder,
      value: el.value,
      visible: el.offsetParent !== null,
      rect: el.getBoundingClientRect(),
      className: el.className?.substring(0, 50),
      ariaLabel: el.getAttribute('aria-label')
    }), inputs[i]);

    if (info.visible && info.rect.height > 0) {
      console.log(`[${i}] ${info.type} id="${info.id}" name="${info.name}" placeholder="${info.placeholder}" visible=${info.visible} y=${Math.round(info.rect.y)} class="${info.className}"`);
    }
  }

  // Also check textareas and contenteditables
  const editables = await page.evaluate(() => {
    const els = document.querySelectorAll('textarea, [contenteditable="true"]');
    return Array.from(els).map(e => ({
      tag: e.tagName,
      id: e.id,
      class: e.className?.substring(0, 50),
      visible: e.offsetParent !== null,
      text: e.textContent?.substring(0, 20)
    }));
  });
  console.log('\nEditables:', JSON.stringify(editables, null, 2));

  browser.disconnect();
}

main().catch(console.error);
