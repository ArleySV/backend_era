# ERA — Casos de Uso

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las
> reglas permanentes del proyecto y la matriz de relevancia para el backend.

---

## CU-01 – Registrarse

**ID:** CU-01
**Nombre:** Registrarse
**Actor principal:** Menor de edad (con datos aportados por el acudiente)
**Precondición:** El usuario no cuenta con una cuenta activa en el sistema.
**Flujo básico:**
1. El usuario selecciona "Registrarse" en la pantalla inicial.
2. Ingresa los datos personales del menor y del acudiente (paso 1).
3. Configura correo, usuario, avatar y contraseña (paso 2).
4. El sistema envía un código de verificación al correo (paso 3).
5. El usuario ingresa el código y el sistema activa la cuenta.

**Flujo alternativo:**
- 3a. Si un campo no es válido, el sistema muestra un mensaje descriptivo y conserva los datos ya ingresados.
- 4a. Si el código expira, el usuario solicita reenvío.
- 5a. Si no hay conexión, se muestra un mensaje de error temporal.

**Postcondición:** La cuenta queda registrada y activa en la base de datos.

---

## CU-02 – Jugar nivel de trivia

**ID:** CU-02
**Nombre:** Jugar nivel de trivia
**Actor principal:** Menor de edad
**Precondición:** El usuario ha iniciado sesión y al menos un nivel está disponible.
**Flujo básico:**
1. El usuario selecciona un nivel disponible en la pantalla de niveles.
2. El sistema presenta la pregunta con el cronómetro de 10 segundos.
3. El usuario selecciona una opción de respuesta.
4. El sistema evalúa la respuesta y muestra la ventana de resultado.
5. Si el nivel se supera, el siguiente nivel se desbloquea automáticamente.

**Flujo alternativo:**
- 3a. Si el cronómetro llega a cero sin respuesta, se considera derrota.
- 4a. Tras 2 fallos consecutivos, el sistema fuerza una pausa de 1 minuto ("Estírate y respira").

**Postcondición:** El progreso y los reintentos del usuario quedan actualizados en la base de datos.

---

## CU-03 – Recuperar contraseña

**ID:** CU-03
**Nombre:** Recuperar contraseña
**Actor principal:** Menor de edad o acudiente (según quién gestione la cuenta)
**Precondición:** El usuario cuenta con una cuenta registrada pero no recuerda su contraseña.
**Flujo básico:**
1. El usuario ingresa su correo registrado y solicita el código.
2. El sistema envía un código de 6 dígitos válido por 10 minutos.
3. El usuario ingresa el código y lo verifica.
4. El usuario define y confirma una nueva contraseña.
5. El sistema redirige al inicio de sesión.

**Flujo alternativo:**
- 1a. Si el correo no está registrado, se muestra un mensaje de error genérico (sin confirmar existencia, por seguridad).
- 4a. Si la nueva contraseña coincide con la anterior, el sistema la rechaza.

**Postcondición:** La contraseña queda actualizada y el código de recuperación invalidado.

---

## CU-04 – Iniciar sesión

**ID:** CU-04
**Nombre:** Iniciar sesión
**Actor principal:** Menor de edad (usuario ya registrado)
**Actor secundario:** Servidor / API (validación de credenciales)
**Precondición:** El usuario cuenta con una cuenta activa registrada previamente.
**Flujo básico:**
1. El usuario abre la aplicación y accede a la pantalla de inicio de sesión.
2. Ingresa su nombre de usuario o correo electrónico y su contraseña.
3. El sistema valida las credenciales contra la base de datos.
4. Si la validación es exitosa, se muestra la pantalla de carga (REQ-FUN-03).
5. El sistema redirige al usuario a la pantalla principal.

**Flujo alternativo:**
- 3a. Si las credenciales son incorrectas, se muestra un mensaje de error genérico sin indicar cuál campo falló.
- 3b. Tras 5 intentos fallidos consecutivos, el sistema bloquea el acceso durante 2 minutos.
- 4a. Si existe una sesión persistente activa, el sistema omite este flujo y accede directo a la pantalla principal.

**Postcondición:** El usuario queda autenticado, con sesión activa en el sistema.

---

## CU-05 – Cerrar sesión

**ID:** CU-05
**Nombre:** Cerrar sesión
**Actor principal:** Menor de edad
**Precondición:** El usuario tiene una sesión activa.
**Flujo básico:**
1. El usuario abre el menú lateral (sidebar) desde la pantalla principal.
2. Selecciona la opción "Cerrar sesión".
3. El sistema muestra un diálogo de confirmación ("¿Deseas cerrar sesión?").
4. El usuario confirma la acción.
5. El sistema invalida la sesión localmente, limpia el token de autenticación y redirige a la pantalla de inicio de sesión.

**Flujo alternativo:**
- 4a. Si el usuario cancela, permanece en la pantalla actual sin cambios.

**Postcondición:** La sesión queda finalizada; el progreso y los datos del usuario se conservan en la base de datos.

---

## CU-06 – Editar cuenta

**ID:** CU-06
**Nombre:** Editar cuenta
**Actor principal:** Menor de edad
**Precondición:** El usuario tiene sesión activa y accede a la pantalla "Mi cuenta".
**Flujo básico:**
1. El usuario selecciona "Mi cuenta" desde el menú lateral.
2. El sistema muestra los datos de la cuenta en modo lectura.
3. El usuario pulsa "Editar" para habilitar los campos editables (nombre de usuario y foto de perfil).
4. El usuario modifica los datos y pulsa "Guardar".
5. El sistema valida la información y la actualiza en la base de datos.

**Flujo alternativo:**
- 3a. El usuario puede cambiar su avatar seleccionando una imagen de la galería interna o del dispositivo.
- 5a. Si algún dato no cumple las validaciones, el sistema muestra un mensaje de error y no guarda los cambios.

**Postcondición:** Los datos de la cuenta quedan actualizados en la base de datos. Los datos del acudiente (nombre y cédula), los nombres del menor, la fecha de nacimiento y el correo electrónico permanecen como solo lectura y no son modificables desde este flujo.

---

## CU-07 – Eliminar cuenta

**ID:** CU-07
**Nombre:** Eliminar cuenta
**Actor principal:** Menor de edad (con conocimiento del acudiente)
**Precondición:** El usuario tiene sesión activa y accede a la pantalla de ajustes.
**Flujo básico:**
1. El usuario selecciona "Eliminar cuenta" en la pantalla de ajustes.
2. El sistema solicita la contraseña actual para confirmar identidad.
3. El usuario ingresa la contraseña correctamente.
4. El sistema muestra un segundo modal de confirmación irreversible.
5. El usuario confirma "Continuar con la eliminación".
6. El sistema marca la cuenta como inactiva/eliminada (soft delete) y redirige al inicio de sesión.

**Flujo alternativo:**
- 3a. Si la contraseña es incorrecta, se muestra un error y no continúa el flujo.
- 5a. Si el usuario cancela en cualquiera de los dos modales, permanece en la pantalla de ajustes.

**Postcondición:** La cuenta queda marcada como eliminada/inactiva; el correo no puede reutilizarse para un nuevo registro.

---

## CU-08 – Consultar progreso

**ID:** CU-08
**Nombre:** Consultar progreso
**Actor principal:** Menor de edad
**Precondición:** El usuario tiene sesión activa.
**Flujo básico:**
1. El usuario selecciona "Progreso" desde el menú lateral.
2. El sistema calcula y muestra el número de niveles completados sobre el total (20).
3. El sistema muestra la barra de progreso animada con el porcentaje calculado dinámicamente.
4. El sistema muestra el total acumulado de reintentos registrados.

**Flujo alternativo:**
- 2a. Si el usuario aún no ha completado ningún nivel, se muestra 0% de progreso.

**Postcondición:** El usuario visualiza el estado actualizado de su avance en la aplicación.

---

## CU-09 – Configurar ajustes

**ID:** CU-09
**Nombre:** Configurar ajustes
**Actor principal:** Menor de edad
**Precondición:** El usuario tiene sesión activa.
**Flujo básico:**
1. El usuario selecciona "Ajustes" desde el menú lateral.
2. El sistema muestra los controles de personalización disponibles (sonido, música, tema visual, tamaño de texto).
3. El usuario modifica una o varias preferencias.
4. El sistema aplica los cambios de forma inmediata y los almacena localmente en el dispositivo.

**Flujo alternativo:**
- 3a. El usuario pulsa "Sincronizar ahora", lo que extiende hacia CU-12 Sincronizar datos.
- 3b. El usuario selecciona "Eliminar cuenta", lo que inicia el flujo de CU-07.

**Postcondición:** Las preferencias del usuario quedan aplicadas y almacenadas localmente.

---

## CU-10 – Consultar FAQ / enviar comentario

**ID:** CU-10
**Nombre:** Consultar FAQ / enviar comentario
**Actor principal:** Menor de edad
**Precondición:** El usuario tiene sesión activa. La consulta de FAQ no requiere conexión a internet.
**Flujo básico:**
1. El usuario selecciona "Preguntas frecuentes" desde el menú lateral.
2. El sistema muestra un listado de tarjetas con preguntas y respuestas frecuentes.
3. El usuario escribe un comentario o sugerencia en el área de texto libre.
4. El usuario pulsa "Enviar".
5. El sistema envía el comentario y muestra una confirmación de recepción.

**Flujo alternativo:**
- 4a. Si el campo de comentarios está vacío, el botón "Enviar" permanece deshabilitado.
- 4b. Si no hay conexión a internet, el envío no puede completarse; la consulta de FAQ sigue disponible.

**Postcondición:** El comentario queda registrado en el sistema (cuando hay conexión); las FAQ quedan consultadas.

---

## CU-11 – Verificar código (correo)

**ID:** CU-11
**Nombre:** Verificar código (correo)
**Tipo:** Caso de uso incluido (<<include>>) por CU-01 Registrarse y CU-03 Recuperar contraseña
**Actor principal:** Servidor / API
**Actor secundario:** Menor de edad o acudiente (según quién gestione la cuenta)
**Precondición:** Se solicitó un registro (CU-01) o una recuperación de contraseña (CU-03) que requiere validar el correo.
**Flujo básico:**
1. El sistema genera y envía un código de verificación de 6 dígitos al correo registrado.
2. El usuario ingresa el código recibido en la aplicación.
3. El sistema valida el código contra el generado y su vigencia (10 minutos).
4. Si es válido, el sistema confirma la verificación y retorna el control al caso de uso que lo incluyó.

**Flujo alternativo:**
- 3a. Si el código expiró o es incorrecto, el sistema muestra un mensaje de error y permite solicitar el reenvío.

**Postcondición:** El correo del usuario queda verificado y el flujo que lo incluyó puede continuar.

---

## CU-12 – Sincronizar datos

**ID:** CU-12
**Nombre:** Sincronizar datos
**Tipo:** Caso de uso de extensión (<<extend>>) de Configurar ajustes y otros módulos que generan datos offline
**Actor principal:** Servidor / API
**Actor secundario:** Menor de edad (dispara la sincronización manual o automática)
**Precondición:** El dispositivo cuenta con conexión a internet disponible.
**Flujo básico:**
1. El sistema detecta conexión a internet disponible (automáticamente o porque el usuario pulsó "Sincronizar ahora").
2. El sistema envía al servidor los datos locales pendientes (progreso, niveles, reintentos, comentarios).
3. El servidor confirma la recepción y devuelve datos actualizados si existen.
4. El sistema actualiza el almacenamiento local con la información sincronizada.

**Flujo alternativo:**
- 1a. Si no hay conexión disponible, el sistema pospone la sincronización y continúa operando en modo offline.
- 3a. Si la sincronización falla, el sistema informa el fallo y reintenta en la siguiente oportunidad de conexión.

**Postcondición:** Los datos locales y del servidor quedan consistentes entre sí. Nota: el avatar personalizado cargado desde el almacenamiento del dispositivo no forma parte de los datos sincronizados con el servidor (ver REQ-FUN-06); solo se sincronizan progreso, niveles, reintentos y comentarios.
