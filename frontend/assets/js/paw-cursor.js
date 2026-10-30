const finePointer = matchMedia('(hover: hover) and (pointer: fine)');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const marks = new Set();
let previous = null;
let side = 1;

function clearTrail() {
  for (const mark of marks) mark.remove();
  marks.clear();
  previous = null;
}

document.addEventListener('pointermove', (event) => {
  if (!finePointer.matches || reducedMotion.matches || event.pointerType !== 'mouse') return;
  if (!previous) {
    previous = { x: event.clientX, y: event.clientY };
    return;
  }
  const dx = event.clientX - previous.x;
  const dy = event.clientY - previous.y;
  const distance = Math.hypot(dx, dy);
  if (distance < 30) return;

  // Alternate the prints beside the path, like small walking steps.
  const mark = document.createElement('span');
  mark.className = 'pc-paw-trail';
  mark.setAttribute('aria-hidden', 'true');
  mark.style.left = `${event.clientX - dy / distance * 5 * side}px`;
  mark.style.top = `${event.clientY + dx / distance * 5 * side}px`;
  mark.style.setProperty('--paw-angle', `${Math.atan2(dy, dx) * 180 / Math.PI + 90}deg`);
  side *= -1;
  previous = { x: event.clientX, y: event.clientY };

  const remove = () => { mark.remove(); marks.delete(mark); };
  mark.addEventListener('animationend', remove, { once: true });
  // Bounded lifetime also covers disabled/interrupted CSS animations.
  setTimeout(remove, 1000);
  marks.add(mark);
  document.body.append(mark);
  if (marks.size > 24) {
    const oldest = marks.values().next().value;
    oldest.remove();
    marks.delete(oldest);
  }
}, { passive: true });

document.addEventListener('pointerleave', clearTrail);
document.addEventListener('scroll', clearTrail, { passive: true, capture: true });
document.addEventListener('visibilitychange', clearTrail);
window.addEventListener('blur', clearTrail);
finePointer.addEventListener('change', clearTrail);
reducedMotion.addEventListener('change', clearTrail);
