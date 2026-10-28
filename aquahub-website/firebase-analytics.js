(function () {
  const sites = {
    'aedev-60c51.web.app': {
      name: 'AeDev', projectId: 'aedev-60c51',
      appId: '1:304666061967:web:b4bbb692a7eda6996e54fa',
      apiKey: 'AIzaSyBXceuEnIu17jnmH_KV1CyKs3px6sFYlnk',
      measurementId: 'G-HGLVPJ8T7T', privacy: '/privacy.html'
    },
    'aedev-60c51.firebaseapp.com': {
      name: 'AeDev', projectId: 'aedev-60c51',
      appId: '1:304666061967:web:b4bbb692a7eda6996e54fa',
      apiKey: 'AIzaSyBXceuEnIu17jnmH_KV1CyKs3px6sFYlnk',
      measurementId: 'G-HGLVPJ8T7T', privacy: '/privacy.html'
    },
    'aquahub.aesprt.com': {
      name: 'AquaHub', projectId: 'aquahub-506411',
      appId: '1:986230110871:web:a3f1f4a39f4a2d22cd07d2',
      apiKey: 'AIzaSyCD5i_yTMd2FmKRuobK3rTXdsnAiMjImGg',
      measurementId: 'G-SE8RN81DB5', privacy: '/privacy.html'
    },
    'aquahub-506411.web.app': {
      name: 'AquaHub', projectId: 'aquahub-506411',
      appId: '1:986230110871:web:a3f1f4a39f4a2d22cd07d2',
      apiKey: 'AIzaSyCD5i_yTMd2FmKRuobK3rTXdsnAiMjImGg',
      measurementId: 'G-SE8RN81DB5', privacy: '/privacy.html'
    }
  };
  const site = sites[location.hostname];
  if (!site) return;
  const key = `${site.projectId}-analytics-consent`;
  let choice = null;
  try { choice = localStorage.getItem(key); } catch { /* Storage may be unavailable. */ }

  async function startAnalytics() {
    try {
      const [{ initializeApp, getApps }, { getAnalytics, isSupported }] = await Promise.all([
        import('https://www.gstatic.com/firebasejs/12.4.0/firebase-app.js'),
        import('https://www.gstatic.com/firebasejs/12.4.0/firebase-analytics.js')
      ]);
      if (!await isSupported()) return;
      const app = getApps().find(value => value.options.appId === site.appId) || initializeApp({
        projectId: site.projectId,
        appId: site.appId,
        apiKey: site.apiKey,
        measurementId: site.measurementId
      }, site.projectId);
      getAnalytics(app);
    } catch (error) {
      console.warn(`${site.name} Analytics could not start.`, error);
    }
  }

  if (choice === 'accepted') { startAnalytics(); return; }
  if (choice === 'rejected') return;
  const banner = document.createElement('aside');
  banner.className = 'analytics-consent';
  banner.setAttribute('aria-label', 'Optional website analytics');
  banner.innerHTML = `<div><strong>Help us understand site visits</strong><p>${site.name} uses optional Firebase Analytics to see how people use this website. You can continue without it. <a href="${site.privacy}">Privacy details</a></p></div><div class="analytics-actions"><button type="button" data-analytics-reject>Not now</button><button type="button" data-analytics-accept>Allow analytics</button></div>`;
  document.body.appendChild(banner);
  function select(value) {
    try { localStorage.setItem(key, value); } catch { /* A storage restriction does not block the site. */ }
    banner.remove();
    if (value === 'accepted') startAnalytics();
  }
  banner.querySelector('[data-analytics-reject]').addEventListener('click', () => select('rejected'));
  banner.querySelector('[data-analytics-accept]').addEventListener('click', () => select('accepted'));
})();
