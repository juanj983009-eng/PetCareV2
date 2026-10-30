# Recursos de imagen — PetCare Enterprise

## Fotografía de bienvenida clínica (Inicio)
- **Archivo local:** `veterinary-exam.jpg`
- **Título de la obra:** Dog portrait with a veterinarian in blue uniform holding a stethoscope
- **Autor / Fotógrafo:** Nenad Stojkovic
- **Fuente original (Flickr):** https://www.flickr.com/photos/nenadstojkovic/51647083727/
- **Repositorio abierto (Wikimedia Commons):** https://commons.wikimedia.org/wiki/File:Dog_portrait_with_a_veterinarian_in_blue_uniform_holding_a_stethoscope_-_51647083727.jpg
- **Licencia:** Creative Commons Attribution 2.0 Generic (CC BY 2.0) — https://creativecommons.org/licenses/by/2.0/
- **Propósito y uso:** Imagen editorial integrada en el banner de bienvenida del panel de inicio clínico (`#dashboard-home`).
- **Modificaciones reales aplicadas:**
  - Redimensionado proporcional a 1280 × 854 px desde el archivo de alta resolución original para optimizar la transferencia HTTP y el rendimiento en el navegador (~95 KB).
  - Encuadre no destructivo mediante CSS (`object-fit: cover` con `object-position` calibrado) para encuadrar nítidamente el rostro del perro, sus ojos, hocico y sonrisa, junto con la veterinaria y el estetoscopio.
  - Integración visual sobre el fondo salvia mediante superposición CSS (`.pc-welcome-photo-blend` y borde flotante blanco con sombra suave), sin alterar los píxeles del archivo base.


## Mascotas decorativas del Inicio (UI-004)

- Archivo: `petcare-companions.webp`. Recurso original generado con la herramienta integrada de generación de imágenes de Codex el 25 de octubre de 2026.
- Uso: perro golden retriever y gato atigrado con pañuelos verdes sobre fondo transparente. Imagen decorativa, sin asociación con pacientes ni profesionales registrados.
- Prompt: “A friendly golden retriever and a silver-brown tabby cat seated side by side, head and upper torso, deep forest green bandanas, soft studio lighting, photographic fur detail, isolated on a true transparent background; no text, logos or interface.”
- Optimización: redimensionado proporcional a un máximo de 1000 px y conversión WebP con transparencia. El original generado permanece fuera del repositorio.


## Retratos ficticios y composición de Agenda (UI-004)

- Recursos originales generados con la herramienta integrada el 25/10/2026. No son fotografías de pacientes reales.
- agenda-companions.webp: cavalier, gato gris y conejo; composición decorativa distinta del Inicio.
- demo-max.webp (beagle), demo-mila.webp (British Shorthair), demo-toby.webp (cavalier), demo-nube.webp (conejo): retratos de cuatro registros ficticios creados mediante las APIs existentes únicamente en petcare-demo.
- Los retratos se asocian por UUID explícito en demo-patient-portraits.js, nunca por nombre ni especie. Si falta el recurso, se restaura el avatar original. No existe aún carga ni almacenamiento de fotos de usuario.
- Optimización proporcional WebP: cabecera hasta 900 px, retratos hasta 400 px. Los originales permanecen fuera del repositorio.


## Nuevos recursos de ficha (26/10/2026)
- profile-companions.webp: border collie y gato siamés, composición decorativa original generada. No identifica al paciente abierto.
- demo-luna.webp: retrato generado de una perrita mestiza ficticia, asociado exclusivamente al UUID existente de Luna en la demo. No modifica especie, raza ni datos clínicos persistidos.


## Ilustración del acceso (26/10/2026)
login-veterinary-scene.webp: composición generada a partir de la referencia aportada por el usuario, con golden retriever, gato y sala veterinaria, sin textos ni controles incrustados. Recurso decorativo para el login. Se conservó la imagen original fuera del repositorio y se optimizó proporcionalmente a WebP.

## PNG transparentes de módulos integrados (26/10/2026)

- `veterinary-team-cutout.png`: dos profesionales ficticios revisando un portapapeles, sin fondo. Decoración de Usuarios; no representa a los usuarios de la base de datos.
- `veterinary-access-cutout.png`: tablet, estetoscopio y acreditación en una composición sin fondo. Decoración de Roles; la pantalla es abstracta y no representa permisos ni funciones disponibles.
- `veterinary-consultation-cutout.png`: profesional ficticia y beagle en una composición sin fondo. Decoración de Evaluación; no identifica al paciente de la consulta abierta.
- Generación: herramienta integrada `imagegen`, solicitando fondo transparente real, paleta verde/salvia y objetos/personas agrupados sobre una base común, sin textos, logotipos ni identidades reales. Originales conservados fuera del repositorio. Redimensionado proporcional a un máximo de 720 × 600 px y guardado PNG optimizado, preservando el canal alfa.
- Los recortes se funden con arcos salvia y apoyan en el borde inferior de las cabeceras, sin marcos ni fondos fotográficos rectangulares. Sustituyen las escenas rectangulares descartadas por el usuario. El buscador de Pacientes reutiliza `profile-companions.webp`; el retrato de la consulta utiliza exclusivamente el mapa por UUID de `demo-patient-portraits.js`, con restauración del avatar original ante error, paciente desconocido o cambio de contexto.
