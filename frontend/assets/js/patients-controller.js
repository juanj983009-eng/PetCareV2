import { ApiRequestError } from "./api-client.js";

const PATIENT_CREATE = "PATIENT_CREATE";
export function createPatientsController({document, client}) {
 const e=elements(document); let allowed=false, loading=false;
 e.open.addEventListener("click",open); e.cancel.addEventListener("click",()=>e.dialog.close()); e.ownerSearch.addEventListener("click",findOwners); e.form.addEventListener("submit",create);
 return Object.freeze({sessionReady(identity){allowed=new Set(identity?.authorities??[]).has(PATIENT_CREATE);e.open.hidden=!allowed;},sessionEnded(){allowed=false;e.open.hidden=true;if(e.dialog.open)e.dialog.close();}});
 function open(){if(!allowed)return;e.form.reset();e.form.classList.remove("was-validated");e.error.hidden=true;e.validation.hidden=true;e.ownerOptions.replaceChildren(new Option("Busca un propietario", ""));e.dialog.showModal();e.ownerQuery.focus();}
 async function findOwners(){const query=e.ownerQuery.value.trim();if(query.length<2){e.error.textContent="Ingresa al menos dos caracteres para buscar al propietario.";e.error.hidden=false;return;} e.ownerSearch.disabled=true; try{const result=await client.requestJson(`/api/v1/owners?${new URLSearchParams({query,page:"0",size:"20",sortBy:"name",direction:"asc"})}`);e.ownerOptions.replaceChildren(new Option("Selecciona un propietario", ""),...result.content.map(owner=>new Option(`${owner.firstName} ${owner.lastName} · ${owner.phone}`,owner.id)));e.error.hidden=true;}catch(error){e.error.textContent=error instanceof ApiRequestError?error.message:"No fue posible buscar propietarios.";e.error.hidden=false;}finally{e.ownerSearch.disabled=false;}}
 async function create(event){event.preventDefault();if(loading||!allowed)return;e.form.classList.add("was-validated");const invalid=[...e.form.querySelectorAll("input,select")].filter(input=>!input.checkValidity());for(const input of e.form.querySelectorAll("input,select")){if(invalid.includes(input))input.setAttribute("aria-invalid","true");else input.removeAttribute("aria-invalid");}if(invalid.length){e.validation.textContent="Corrige los campos indicados antes de registrar el paciente.";e.validation.hidden=false;invalid[0].focus();return;}loading=true;e.save.disabled=true;e.error.hidden=true;try{await client.requestJson("/api/v1/patients",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({ownerId:e.ownerOptions.value,name:e.name.value,species:e.species.value,breed:e.breed.value,sex:e.sex.value,birthDate:e.birthDate.value||null,color:e.color.value,microchip:e.microchip.value,sterilized:e.sterilized.checked})});e.dialog.close();e.notice.hidden=false;e.notice.focus();}catch(error){e.error.textContent=error instanceof ApiRequestError?error.message:"No fue posible registrar el paciente.";e.error.hidden=false;}finally{loading=false;e.save.disabled=false;}}
}
function elements(document){const s={open:"#new-patient-button",dialog:"#patient-editor-dialog",form:"#patient-editor-form",ownerQuery:"#patient-owner-query",ownerSearch:"#patient-owner-search",ownerOptions:"#patient-owner-id",name:"#patient-name",species:"#patient-species",breed:"#patient-breed",sex:"#patient-sex",birthDate:"#patient-birth-date",color:"#patient-color",microchip:"#patient-microchip",sterilized:"#patient-sterilized",save:"#save-patient-button",cancel:"#cancel-patient-button",error:"#patient-form-error",validation:"#patient-form-validation",notice:"#patient-notice"};const e=Object.fromEntries(Object.entries(s).map(([k,v])=>[k,document.querySelector(v)]));for(const [k,v] of Object.entries(e))if(!v)throw new Error(`Falta ${k}`);return e;}
function bindPatientValidationDescriptions(document) {
  const form = document.querySelector("#patient-editor-form");
  if (!form) return;
  const fields = [
    ["#patient-owner-id", "patient-owner-id-feedback"],
    ["#patient-name", "patient-name-feedback"],
    ["#patient-species", "patient-species-feedback"],
    ["#patient-sex", "patient-sex-feedback"]
  ];
  const feedback = [...form.querySelectorAll(".invalid-feedback")];
  fields.forEach(([selector, id], index) => {
    const field = form.querySelector(selector);
    const message = feedback[index];
    if (field && message) {
      message.id = id;
      field.setAttribute("aria-describedby", id);
    }
  });
}

bindPatientValidationDescriptions(document);
