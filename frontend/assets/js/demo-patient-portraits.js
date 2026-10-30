// Generated portraits belong only to these explicitly created fictional demo records.
// They are illustrative, sometimes shared portraits, not patient uploads.
// Associations are explicit IDs; never select an image by name or species.
const portraits = Object.freeze({
  "e949393f-77aa-4e49-8d27-bddcbd4e0fbc": "luna",
  "5aa9b2c7-ae6e-471f-9b44-21fd8e4a380f": "max",
  "a91fd4cb-4d19-4b8e-a413-d3c16ad30778": "mila",
  "a32cf10b-60c6-48d7-a7d5-83d0cdd0624f": "toby",
  "1f3c8f13-b674-41ae-b51f-66159adf0073": "nube",
  "f93bf76c-1eb9-4ac4-a32a-1dcc1384d293": "luna",
  "d7591310-30e6-4d67-a94b-63b7b39ffbca": "toby",
  "dde2565f-6977-4535-b5fe-cc67044a8d43": "mila",
  "cd64330b-f988-4967-ab06-ef93bb4546a7": "mila",
  "2c3c8019-b9b7-4ef7-b2f8-36726d7b62ce": "mila",
  "08b07d07-f7a2-49b9-961e-81ecfc8ee5a4": "luna",
  "83401d16-5a48-42ad-9fb5-591de2e5efd2": "max"
});

export function applyDemoPatientPortrait(container, patientId) {
  const key = Object.hasOwn(portraits, patientId) ? portraits[patientId] : null;
  if (!key || !container) return false;
  const fallback = [...container.childNodes].map(node => node.cloneNode(true));
  const img = container.ownerDocument.createElement('img');
  img.src = '/assets/img/demo-' + key + '.webp';
  img.alt = '';
  img.width = 80;
  img.height = 80;
  img.loading = 'lazy';
  img.className = 'pc-demo-patient-photo';
  img.title = 'Retrato ilustrativo generado para datos ficticios de demostración';
  img.addEventListener('error', () => {
    if (img.parentNode === container) container.replaceChildren(...fallback);
  }, { once: true });
  container.replaceChildren(img);
  return true;
}
