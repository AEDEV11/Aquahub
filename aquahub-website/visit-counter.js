(function () {
  const site = {
    'aedev-60c51.web.app': 'aedev',
    'aedev-60c51.firebaseapp.com': 'aedev',
    'aquahub.aesprt.com': 'aquahub',
    'aquawash.aesprt.com': 'aquawash'
  }[location.hostname];
  if (!site) return;

  const key = `aedev-visit-counted-${site}`;
  if (sessionStorage.getItem(key)) return;
  const url = `https://aedev-60c51-default-rtdb.asia-southeast1.firebasedatabase.app/visits/${site}.json`;
  fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ '.sv': { increment: 1 } }),
    cache: 'no-store'
  }).then(response => {
    if (!response.ok) throw new Error(`Visit counter returned ${response.status}`);
    sessionStorage.setItem(key, '1');
    if (site === 'aedev') window.dispatchEvent(new Event('aedev:visit-counted'));
  }).catch(() => {});
})();
