# ERA — Requisitos Funcionales

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las
> reglas permanentes del proyecto y la matriz de relevancia para el backend.

---

## REQ-FUN-01 – Registro de usuario

**ID:** REQ-FUN-01
**Nombre:** Registro de usuario
**Descripción:** Al abrir la aplicación por primera vez, se mostrarán dos opciones: registro (usuarios nuevos) e inicio de sesión (usuarios ya registrados).

El flujo de registro se divide en tres pasos:
- Paso 1: Datos personales: nombres completos del menor, fecha de nacimiento, nombre completo del acudiente y número de cédula del acudiente.
- Paso 2: Configuración de cuenta: correo principal, nombre de usuario, selección de avatar (galería interna o imagen del dispositivo), contraseña y confirmación de contraseña.
- Paso 3: Verificación de correo: el sistema envía un código de 6 dígitos al correo registrado; el usuario lo ingresa para activar la cuenta.

El sistema validará todos los campos antes de permitir avanzar al paso siguiente. En caso de error, se mostrará un mensaje descriptivo sin perder los datos ya ingresados.

Cuando la validación sea exitosa, los datos se almacenarán en la base de datos y se enviará el código de confirmación.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. Un mismo correo electrónico no puede registrar más de una cuenta activa.
2. La contraseña debe tener mínimo 8 caracteres, combinando letras mayúsculas, minúsculas, números y símbolos especiales; no debe contener datos personales del usuario y no puede ser igual al nombre de usuario.
3. La fecha de nacimiento debe ser una fecha válida; la edad del menor se calculará dinámicamente a partir de ella.
4. El código de verificación consta de 6 dígitos numéricos y tiene una validez de 10 minutos. El usuario puede solicitar reenvío del código.
5. Si hay fallo de conexión, el sistema mostrará un mensaje de error temporal y conservará los datos ya ingresados por el usuario.
6. Se requiere conexión a internet para completar el registro y verificar el correo.

---

## REQ-FUN-02 – Identificación del usuario (inicio de sesión)

**ID:** REQ-FUN-02
**Nombre:** Identificación del usuario (inicio de sesión)
**Descripción:** La pantalla de inicio de sesión solicitará al usuario su nombre de usuario o correo electrónico y su contraseña. El sistema validará las credenciales contra la base de datos. Si la validación es incorrecta, se mostrará un mensaje de error genérico sin revelar cuál campo es incorrecto (buena práctica de seguridad). Si la validación es exitosa, se mostrará una pantalla de carga y luego se redirigirá al usuario a la pantalla principal.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. Si el usuario ya había iniciado sesión previamente y no cerró sesión de forma explícita, al abrir la app se mostrará directamente la pantalla principal (sesión persistente).
2. El sistema debe validar usuario/correo y contraseña antes de redirigir a la pantalla principal.
3. Tras 5 intentos fallidos consecutivos, el sistema bloqueará temporalmente el acceso durante 2 minutos (buena práctica de seguridad).

---

## REQ-FUN-03 – Pantalla de carga

**ID:** REQ-FUN-03
**Nombre:** Pantalla de carga
**Descripción:** Después de un inicio de sesión exitoso, el sistema mostrará una pantalla de transición antes de cargar la pantalla principal. Esta pantalla presentará: el nombre de la aplicación, imágenes alusivas y una frase motivacional o educativa. La pantalla de carga se mostrará mientras el sistema inicializa el estado de la sesión y carga los datos del usuario (niveles, progreso, configuración).

**Prioridad:** Media
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. La pantalla de carga no debe superar los 3 segundos de duración (ver REQ-NF-01).
2. Si los datos ya están en caché local, la duración puede reducirse a 1 segundo para mejorar la experiencia.
3. No se mostrarán controles interactivos durante esta pantalla; el usuario no puede navegar hacia atrás desde ella.
4. Si la carga falla (error de conexión o datos corruptos), el sistema mostrará un mensaje de error y ofrecerá reintentar o cerrar sesión.

---

## REQ-FUN-04 – Cierre de sesión

**ID:** REQ-FUN-04
**Nombre:** Cierre de sesión
**Descripción:** Cualquier usuario autenticado podrá finalizar su sesión mediante la opción "Cerrar sesión" ubicada en el menú lateral (sidebar). Antes de ejecutar el cierre, el sistema mostrará un diálogo de confirmación para evitar cierres accidentales.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. Al pulsar "Cerrar sesión", el sistema desplegará un mensaje de advertencia: "¿Deseas cerrar sesión?" con opciones "Sí, cerrar sesión" y "Cancelar".
2. Si el usuario confirma, la sesión se invalida localmente, se limpia el token de autenticación y se redirige a la pantalla de inicio de sesión (P-01).
3. Si el usuario cancela, permanece en la pantalla actual sin cambios.
4. El progreso y los datos del usuario se conservan en la base de datos tras el cierre de sesión.

---

## REQ-FUN-05 – Eliminación de cuenta

**ID:** REQ-FUN-05
**Nombre:** Eliminación de cuenta
**Descripción:** El usuario podrá solicitar la eliminación de su cuenta desde la pantalla de ajustes. El flujo de eliminación requerirá confirmación por contraseña para garantizar que la acción es intencional.

Flujo detallado:
1. El usuario selecciona la opción "Eliminar cuenta" en la pantalla de ajustes.
2. Aparece una ventana modal solicitando la contraseña actual para confirmar la identidad.
3. Si la contraseña es correcta, se muestra un segundo modal de confirmación irreversible con el mensaje: "Esta acción no tiene vuelta atrás. Tu cuenta será desactivada de forma permanente." y dos botones: "Continuar con la eliminación" y "Cancelar".
4. Si el usuario confirma, la cuenta queda marcada como eliminada en la base de datos (estado inactivo) y se redirige a la pantalla de inicio de sesión.

Nota: Los datos del usuario NO se eliminan físicamente de la base de datos. En su lugar, la cuenta se marca con un estado eliminada e inactiva, preservando la integridad referencial del sistema y permitiendo auditoría posterior.

**Prioridad:** Baja
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. La opción "Eliminar cuenta" estará ubicada en la pantalla de ajustes, claramente diferenciada con color de alerta.
2. Se requiere contraseña correcta para iniciar el flujo de eliminación; si la contraseña es incorrecta, se muestra un mensaje de error y no se continúa.
3. Se mostrará un segundo paso de confirmación explícita indicando que la acción es irreversible.
4. Una vez confirmada, la cuenta pasa a estado inactiva/eliminada en la base de datos (soft delete); los datos no se borran físicamente.
5. Una cuenta eliminada no podrá iniciar sesión; si lo intenta, el sistema mostrará un mensaje indicando que la cuenta no está activa.
6. El correo electrónico asociado a una cuenta eliminada no podrá reutilizarse para registrar una nueva cuenta (hasta que un administrador lo libere).

---

## REQ-FUN-06 – Cuenta del usuario

**ID:** REQ-FUN-06
**Nombre:** Cuenta del usuario
**Descripción:** El sistema dispondrá de una pantalla donde el usuario pueda consultar y modificar los datos de su cuenta, así como cambiar su avatar de perfil. Los campos editables incluyen: cambiar foto de perfil y modificar nombre de usuario. El correo electrónico, los nombres del menor, la fecha de nacimiento, y el nombre y cédula del acudiente se mostrarán como campos de solo lectura, dado que corresponden a los datos de identidad y autorización registrados en el alta de la cuenta.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. El usuario debe pulsar el botón "Editar" para habilitar los campos; en modo lectura los campos están bloqueados.
2. Al pulsar "Guardar", el sistema validará los datos y, si son correctos, actualizará la información en la base de datos de forma inmediata.
3. Si algún dato no cumple las validaciones, se mostrará un mensaje de error y no se guardará ningún cambio.
4. El avatar puede cambiarse seleccionando una imagen de la galería interna o cargando una desde el almacenamiento del dispositivo.
5. Solo `avatar` y `nombre de usuario` son aceptados como campos modificables por el sistema; cualquier otro campo enviado en la solicitud de actualización será ignorado.

---

## REQ-FUN-07 – Recuperación de contraseña

**ID:** REQ-FUN-07
**Nombre:** Recuperación de contraseña
**Descripción:** El sistema permitirá al usuario recuperar el acceso a su cuenta en caso de haber olvidado su contraseña. El flujo completo se realiza en una sola pantalla con tres secciones secuenciales:
1. Ingreso de correo: el usuario introduce su correo registrado y pulsa "Enviar código".
2. Verificación: el sistema envía un código de 6 dígitos al correo; el usuario lo introduce y pulsa "Verificar código".
3. Nueva contraseña: el usuario ingresa y confirma la nueva contraseña, luego pulsa "Guardar contraseña". Al finalizar se redirige al login.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. El correo ingresado debe estar registrado en el sistema; si no existe, se mostrará un mensaje de error genérico (por seguridad no se confirma si el correo existe o no).
2. El código de recuperación tiene una validez de 10 minutos; vencido este tiempo, el usuario debe solicitar uno nuevo.
3. La nueva contraseña debe cumplir los mismos criterios definidos en REQ-FUN-01 (mínimo 8 caracteres, mayúsculas, minúsculas, números y símbolos).
4. No se puede reutilizar la contraseña anterior.
5. Al guardar correctamente, el sistema invalida el código usado y redirige al inicio de sesión.

---

## REQ-FUN-08 – Menú lateral (Sidebar)

**ID:** REQ-FUN-08
**Nombre:** Menú lateral (Sidebar)
**Descripción:** El menú lateral se activa mediante el ícono de tres barras horizontales (hamburguesa) ubicado en la esquina superior izquierda de la pantalla principal. Al desplegarse, mostrará en la cabecera el avatar del usuario con sus iniciales, el nombre de usuario y el correo. Las opciones de navegación disponibles son: Mi cuenta, Progreso, Ajustes y Preguntas frecuentes, cada una con su ícono representativo. Al final del panel aparecerá la opción "Cerrar sesión" resaltada en color de alerta. El panel se cierra al tocar fuera de él.

**Prioridad:** Media
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. El menú lateral solo estará disponible desde la pantalla principal; no se mostrará dentro de los niveles de trivia ni en otras pantallas secundarias.
2. Cada opción del menú redirige a la pantalla correspondiente.
3. "Cerrar sesión" desplegará el diálogo de confirmación definido en REQ-FUN-04.

---

## REQ-FUN-09 – Pantalla principal

**ID:** REQ-FUN-09
**Nombre:** Pantalla principal
**Descripción:** Es la pantalla central de la aplicación tras autenticarse correctamente. Contiene un saludo personalizado al usuario y acceso directo a los modos de juego disponibles. Incluye un botón principal para ingresar al menú de niveles de Trivia escolar / cultura general de nivel primaria. Incluye adicionalmente un botón secundario con la etiqueta "Próximamente", reservado para futuros modos de juego o temáticas.

**Prioridad:** Alta
**Fuente:** Formulario recolección de requisitos
**Criterios de aceptación:**
1. El usuario debe haber iniciado sesión correctamente para acceder a esta pantalla.
2. Al pulsar el botón de trivia se abre la pantalla de niveles (P-07) con los 20 niveles organizados en orden consecutivo.
3. El botón "Próximamente" es visible pero no ejecuta ninguna acción (estado deshabilitado).
4. El menú lateral estará disponible desde esta pantalla a través del ícono hamburguesa.

---

## REQ-FUN-10 – Pantalla de niveles de trivia

**ID:** REQ-FUN-10
**Nombre:** Pantalla de niveles de trivia
**Descripción:** La pantalla mostrará los 20 niveles de trivia organizados en orden consecutivo. Cada nivel se representará con un círculo numerado cuyo color indica su estado:
- Azul: disponible (el usuario puede jugarlo).
- Verde: completado (ya fue superado).
- Gris: bloqueado (aún no se ha desbloqueado).

Solo los niveles en estado "disponible" o "completado" responden a la interacción del usuario; los bloqueados no son clicables. Un nivel se desbloquea automáticamente cuando el usuario completa el nivel anterior.

**Prioridad:** Alta
**Fuente:** Formulario recolección de requisitos
**Criterios de aceptación:**
1. Al iniciar la app por primera vez, solo el nivel 1 estará disponible.
2. Al completar un nivel, el siguiente cambia de estado "bloqueado" a "disponible" de forma automática.
3. El estado de cada nivel se persiste en la base de datos y se sincroniza al iniciar sesión.
4. Cada nivel contiene exactamente una pregunta con 3 opciones de respuesta (solo una correcta).
5. Algunos niveles podrán incluir una imagen alusiva junto a la pregunta.
6. En la parte superior de la pantalla de cada nivel se mostrará un menú con opciones "Salir" y "Continuar".

---

## REQ-FUN-11 – Comportamiento del cronómetro y lógica de juego

**ID:** REQ-FUN-11
**Nombre:** Comportamiento del cronómetro y lógica de juego
**Descripción:** Cada pregunta dentro de un nivel dispondrá de un cronómetro regresivo de 10 segundos. El cronómetro será visible en la parte superior de la pantalla durante la pregunta.

Condición de victoria: el usuario selecciona la respuesta correcta antes de que el cronómetro llegue a cero.
Condición de derrota: el cronómetro llega a cero sin que el usuario haya respondido, o el usuario selecciona una respuesta incorrecta.

Manejo de intentos y pausa:
- El usuario dispone de intentos ilimitados por nivel.
- Después de 2 intentos fallidos consecutivos en el mismo nivel, el sistema mostrará automáticamente una ventana "Estírate y respira. Tómate un momento." El usuario deberá esperar 1 minuto antes de poder reintentar (cronómetro visible de 60 segundos).

Ventana de resultado:
- Al superar el nivel: ventana inferior de felicitación, el sistema espera 3 segundos y continúa automáticamente al siguiente nivel disponible.
- Al perder: ventana inferior de motivación, el sistema espera 3 segundos y reinicia el nivel.

Pantalla de menú: Pantalla que se despliega cuando el usuario oprime el botón "x" en la parte superior, y que contiene una imagen y las opciones: continuar, reiniciar y salir.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. El cronómetro inicia automáticamente al cargar la pregunta; no puede ser pausado por el usuario.
2. Al seleccionar una respuesta, el cronómetro se detiene de inmediato y se procesa el resultado.
3. El contador de intentos fallidos se reinicia al superar el nivel o al salir de la pantalla de niveles.
4. La ventana de "Estírate y respira" mostrará una cuenta regresiva visible de 60 segundos; al finalizar el cronómetro reiniciará el nivel.
5. El número de reintentos por nivel y a nivel general se registra en la base de datos para mostrarse en la pantalla de progreso.

---

## REQ-FUN-12 – Pantalla de progreso

**ID:** REQ-FUN-12
**Nombre:** Pantalla de progreso
**Descripción:** Accesible desde el menú lateral (sidebar o menú hamburguesa), esta pantalla presentará un resumen visual del avance del usuario en la aplicación. Mostrará: número de niveles completados sobre el total (20), barra de progreso animada con el porcentaje calculado dinámicamente según el estado real de los niveles, y el total de reintentos registrados a nivel general.

**Prioridad:** Media
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. El porcentaje de progreso se calcula como: (niveles completados / 20) × 100.
2. La barra de progreso se actualiza en tiempo real al regresar a esta pantalla tras completar un nivel.
3. El total de reintentos refleja la suma acumulada de todos los intentos fallidos registrados en la sesión y sesiones anteriores.

---

## REQ-FUN-13 – Ajustes

**ID:** REQ-FUN-13
**Nombre:** Ajustes
**Descripción:** La pantalla de ajustes, accesible desde el menú lateral, permitirá al usuario personalizar la experiencia de uso de la aplicación. Los controles disponibles son:
- Efectos de sonido: activar/desactivar (toggle).
- Música de fondo: activar/desactivar (toggle).
- Modo visual: alternar entre modo claro y modo oscuro.
- Tamaño del texto: selector con opciones pequeño, mediano y grande.
- Sincronizar ahora: botón que fuerza la sincronización inmediata de los datos del usuario con el servidor.
- Eliminar cuenta: opción para iniciar el flujo definido en REQ-FUN-05 (resaltada en color de alerta).

**Prioridad:** Media
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. Todas las preferencias de ajustes se almacenarán localmente en el dispositivo y se aplicarán de forma inmediata sin necesidad de reiniciar la app.
2. El modo oscuro/claro afectará a todas las pantallas de la aplicación.
3. El tamaño del texto afectará todos los textos de contenido de la aplicación.
4. El botón "Sincronizar ahora" mostrará un indicador de carga mientras se realiza la operación y confirmará éxito o fallo al finalizar.
5. La opción "Eliminar cuenta" iniciará el flujo descrito en REQ-FUN-05.

---

## REQ-FUN-14 – Preguntas frecuentes y comentarios

**ID:** REQ-FUN-14
**Nombre:** Preguntas frecuentes y comentarios
**Descripción:** Accesible desde el menú lateral, esta sección informará al usuario sobre el funcionamiento de la aplicación y permitirá enviar comentarios o sugerencias a los desarrolladores. Mostrará un mínimo de tres tarjetas con preguntas y respuestas frecuentes (por ejemplo: cómo funciona la app, uso sin internet, desbloqueo de niveles). Al final incluirá un área de texto libre y un botón "Enviar" para que el usuario envíe comentarios.

**Prioridad:** Baja
**Fuente:** Reunión integrantes del equipo
**Criterios de aceptación:**
1. Al acceder a esta sección desde el menú lateral, se visualizará el listado de preguntas frecuentes con sus respuestas.
2. El campo de comentarios permitirá texto libre; el botón "Enviar" estará deshabilitado si el campo está vacío.
3. Al enviar un comentario, el sistema mostrará una confirmación de recepción.
4. Esta sección estará disponible sin necesidad de conexión a internet para las preguntas frecuentes; el envío de comentarios requiere conexión.
