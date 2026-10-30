import { applyDemoPatientPortrait } from '../assets/js/demo-patient-portraits.js';
const knownId = '5aa9b2c7-ae6e-471f-9b44-21fd8e4a380f';
const tests = [];
const test = (name,fn) => tests.push({name,fn});
const assert = (value,message) => { if(!value) throw new Error(message); };
const avatar = () => { const el=document.createElement('span');el.textContent='M';return el; };
test('solo UUID explícitos reciben un retrato, sin alterar pacientes desconocidos',()=>{
 const el=avatar();assert(!applyDemoPatientPortrait(el,'unknown'),'UUID desconocido');
 assert(el.textContent==='M'&&!el.querySelector('img'),'Conserva inicial');
 assert(!applyDemoPatientPortrait(el,'__proto__'),'No acepta propiedades heredadas');
 assert(applyDemoPatientPortrait(el,knownId),'UUID de demo reconocido');
 assert(el.querySelector('img').getAttribute('src')==='/assets/img/demo-max.webp','Usa recurso local permitido');
});
test('fallo de imagen restaura el avatar anterior',()=>{
 const el=avatar();applyDemoPatientPortrait(el,knownId);el.querySelector('img').dispatchEvent(new Event('error'));
 assert(el.textContent==='M'&&!el.querySelector('img'),'Restaura fallback');
});
test('error tardío no sobrescribe contenido de otro paciente',()=>{
 const el=avatar();applyDemoPatientPortrait(el,knownId);const old=el.querySelector('img');
 el.textContent='Paciente B';old.dispatchEvent(new Event('error'));
 assert(el.textContent==='Paciente B','Respeta el contexto actual');
});
test('retrato tiene dimensiones estables y no se anuncia como fotografía real',()=>{
 const el=avatar();applyDemoPatientPortrait(el,knownId);const img=el.querySelector('img');
 assert(img.width===80&&img.height===80,'Dimensiones declaradas');
 assert(img.alt===''&&img.title.includes('generado'),'Decorativo con procedencia explícita');
});
let passed=0;
for(const t of tests){const li=document.createElement('li');try{await t.fn();passed++;li.textContent='PASS '+t.name;}catch(e){li.textContent='FAIL '+t.name+': '+e.message;}document.querySelector('#test-results').append(li);}
document.querySelector('#test-summary').textContent=passed+'/'+tests.length+' pruebas aprobadas';document.body.dataset.testStatus=passed===tests.length?'PASS':'FAIL';
