const puppeteer = require('puppeteer');

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function addRecord(cf, type, name, content) {
  console.log(`\n=== Adding ${type}: ${name} -> ${content} ===`);

  // Cancel any previous form first
  await cf.evaluate(() => {
    const btns = document.querySelectorAll('button');
    const cancel = Array.from(btns).find(b => b.textContent.trim() === 'Cancel');
    if (cancel) cancel.click();
  });
  await sleep(500);

  // 1. Click "Add record"
  await cf.evaluate(() => {
    const btns = document.querySelectorAll('button');
    const btn = Array.from(btns).find(b => b.textContent.trim() === 'Add record');
    if (btn) btn.click();
  });
  await sleep(1500);

  // 2. Set Type - find the react-select input under Type label
  if (type !== 'A') {
    const typeSet = await cf.evaluate((recordType) => {
      const labels = document.querySelectorAll('label');
      for (const l of labels) {
        if (l.textContent.trim() !== 'Type') continue;
        // Find react-select input in the same form group
        const parent = l.parentElement;
        // Go up until we find the container with the react-select
        let container = parent;
        for (let i = 0; i < 5; i++) {
          const rsInput = container.querySelector('input[id*="react-select"]');
          if (rsInput) {
            rsInput.focus();
            rsInput.click();
            return { found: true, id: rsInput.id };
          }
          container = container.parentElement;
          if (!container) break;
        }
      }
      return { found: false };
    }, type);
    console.log('Type select:', JSON.stringify(typeSet));

    if (typeSet.found) {
      // Clear and type
      await sleep(300);
      // Select all text and delete
      await cf.keyboard.down('Meta');
      await cf.keyboard.press('a');
      await cf.keyboard.up('Meta');
      await cf.keyboard.press('Backspace');
      await sleep(200);
      await cf.keyboard.type(type, { delay: 50 });
      await sleep(800);
      await cf.keyboard.press('Enter');
      await sleep(1000);
      console.log('Selected type:', type);
    }
  }

  // Verify what content field is visible now
  const contentFieldInfo = await cf.evaluate(() => {
    const labels = document.querySelectorAll('label');
    for (const l of labels) {
      const text = l.textContent.trim().toLowerCase();
      if (text.includes('ipv4') || text.includes('ipv6') || text.includes('target') || text.includes('address')) {
        return { label: l.textContent.trim(), htmlFor: l.htmlFor };
      }
    }
    return 'no content label found';
  });
  console.log('Content field visible:', JSON.stringify(contentFieldInfo));

  // 3. Set Name field (TEXTAREA under Name label)
  const nameSet = await cf.evaluate((nameValue) => {
    const labels = document.querySelectorAll('label');
    for (const l of labels) {
      if (!l.textContent.trim().startsWith('Name')) continue;
      const container = l.parentElement;
      const textarea = container.querySelector('textarea');
      if (textarea) {
        const nativeSetter = Object.getOwnPropertyDescriptor(
          window.HTMLTextAreaElement.prototype, 'value'
        ).set;
        nativeSetter.call(textarea, nameValue);
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
        textarea.dispatchEvent(new Event('change', { bubbles: true }));
        return 'set via textarea: ' + textarea.id;
      }
    }
    return 'NOT FOUND';
  }, name);
  console.log('Name:', nameSet);

  // 4. Set Content field - find by label
  const contentSet = await cf.evaluate((value) => {
    const labels = document.querySelectorAll('label');
    for (const l of labels) {
      const text = l.textContent.trim().toLowerCase();
      if (text.includes('ipv4') || text.includes('ipv6') || text.includes('target')) {
        const forId = l.htmlFor;
        const el = forId ? document.getElementById(forId) : null;
        if (el) {
          const proto = el.tagName === 'TEXTAREA'
            ? window.HTMLTextAreaElement.prototype
            : window.HTMLInputElement.prototype;
          const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value').set;
          nativeSetter.call(el, value);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
          return 'set: ' + el.id;
        }
        // Fallback: find input in container
        const container = l.parentElement;
        const inp = container.querySelector('input, textarea');
        if (inp) {
          const proto = inp.tagName === 'TEXTAREA'
            ? window.HTMLTextAreaElement.prototype
            : window.HTMLInputElement.prototype;
          const ns = Object.getOwnPropertyDescriptor(proto, 'value').set;
          ns.call(inp, value);
          inp.dispatchEvent(new Event('input', { bubbles: true }));
          inp.dispatchEvent(new Event('change', { bubbles: true }));
          return 'set via container: ' + inp.id;
        }
      }
    }
    return 'NOT FOUND';
  }, content);
  console.log('Content:', contentSet);

  // 5. Disable proxy
  const proxySet = await cf.evaluate(() => {
    const cb = document.getElementById('proxied');
    if (cb && cb.checked) {
      cb.click();
      return 'unchecked';
    }
    return cb ? 'already unchecked' : 'not found';
  });
  console.log('Proxy:', proxySet);
  await sleep(300);

  // Screenshot
  await cf.screenshot({ path: `/tmp/cf-before-${type}.png` });

  // 6. Save
  const saved = await cf.evaluate(() => {
    const btns = document.querySelectorAll('button');
    const btn = Array.from(btns).find(b => b.textContent.trim() === 'Save');
    if (btn) { btn.click(); return true; }
    return false;
  });
  console.log('Save:', saved);
  await sleep(2000);

  const errors = await cf.evaluate(() => {
    const els = document.querySelectorAll('[role="alert"], [class*="error"]');
    return Array.from(els).filter(e => e.offsetParent !== null).map(e => e.textContent.trim().substring(0, 100));
  });
  if (errors.length > 0) console.log('ERRORS:', errors);

  await cf.screenshot({ path: `/tmp/cf-after-${type}.png` });
}

(async () => {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  const cf = pages.find(p => p.url().includes('cloudflare.com') && p.url().includes('dns'));
  if (!cf) { console.log('No CF tab'); return; }
  await cf.bringToFront();

  // Add CNAME record
  await addRecord(cf, 'CNAME', '_acme-challenge', 'karmarent.app.knkqdwo.flydns.net');

})().catch(e => console.error('Error:', e.message));
