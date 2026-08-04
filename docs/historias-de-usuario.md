# ERA — Historias de Usuario

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las
> reglas permanentes del proyecto y la matriz de relevancia para el backend.

---

## HU-01
**Requisito relacionado:** REQ-FUN-01
**Historia de usuario:** Como usuario menor de edad (con datos aportados por mi acudiente), quiero registrarme en la aplicación, para poder acceder a un espacio educativo seguro y personalizado.
**Criterios de aceptación:**
1. El sistema no permite más de una cuenta activa por correo electrónico.
2. La contraseña exige mínimo 8 caracteres con mayúsculas, minúsculas, números y símbolos.
3. El código de verificación de 6 dígitos tiene una validez de 10 minutos y puede reenviarse.
4. Si falla la conexión, los datos ya ingresados no se pierden.
**Prioridad:** Alta

---

## HU-02
**Requisito relacionado:** REQ-FUN-02
**Historia de usuario:** Como usuario registrado, quiero iniciar sesión con mi usuario o correo y mi contraseña, para retomar mi progreso y seguir aprendiendo donde lo dejé.
**Criterios de aceptación:**
1. Si ya inicié sesión antes y no cerré sesión, la app abre directo en la pantalla principal.
2. El sistema valida usuario/correo y contraseña antes de redirigir.
3. Tras 5 intentos fallidos consecutivos, el acceso se bloquea 2 minutos.
**Prioridad:** Alta

---

## HU-03
**Requisito relacionado:** REQ-FUN-03
**Historia de usuario:** Como usuario, quiero ver una pantalla de carga breve y motivadora al ingresar, para tener una transición agradable mientras el sistema prepara mi información.
**Criterios de aceptación:**
1. La pantalla de carga no supera los 3 segundos (1 segundo si los datos ya están en caché).
2. No se muestran controles interactivos ni se puede navegar hacia atrás durante la carga.
3. Si la carga falla, se ofrece reintentar o cerrar sesión.
**Prioridad:** Media

---

## HU-04
**Requisito relacionado:** REQ-FUN-04
**Historia de usuario:** Como usuario, quiero cerrar mi sesión de forma segura y con confirmación, para evitar salidas accidentales y proteger mi cuenta si otra persona usa el dispositivo.
**Criterios de aceptación:**
1. Al pulsar "Cerrar sesión" se solicita confirmación antes de ejecutar la acción.
2. Al confirmar, se invalida la sesión y se limpia el token de autenticación.
3. Mi progreso y mis datos se conservan en la base de datos tras cerrar sesión.
**Prioridad:** Alta

---

## HU-05
**Requisito relacionado:** REQ-FUN-05
**Historia de usuario:** Como usuario (o acudiente), quiero poder eliminar mi cuenta con doble confirmación, para dejar de usar la aplicación cuando lo decida, sin perder la trazabilidad de mis datos.
**Criterios de aceptación:**
1. Se requiere la contraseña actual para iniciar el flujo de eliminación.
2. Existe un segundo paso de confirmación que advierte que la acción es irreversible.
3. La cuenta pasa a estado inactivo/eliminado (soft delete); los datos no se borran físicamente.
4. Una cuenta eliminada no puede volver a iniciar sesión ni reutilizar su correo.
**Prioridad:** Baja

---

## HU-06
**Requisito relacionado:** REQ-FUN-06
**Historia de usuario:** Como usuario, quiero consultar los datos de mi cuenta y editar mi nombre de usuario y avatar, para mantener actualizada mi identidad dentro de la aplicación.
**Criterios de aceptación:**
1. Los campos están bloqueados hasta pulsar "Editar".
2. Solo el nombre de usuario y el avatar son editables; al guardar, el sistema valida los datos y actualiza la base de datos de inmediato.
3. Si un dato no es válido, se muestra un error y no se guarda ningún cambio.
4. El correo electrónico, los nombres del menor, la fecha de nacimiento, y el nombre y cédula del acudiente se muestran siempre como solo lectura.
**Prioridad:** Alta

---

## HU-07
**Requisito relacionado:** REQ-FUN-07
**Historia de usuario:** Como usuario, quiero recuperar el acceso a mi cuenta si olvido mi contraseña, para no perder mi progreso ni tener que crear una cuenta nueva.
**Criterios de aceptación:**
1. Si el correo no está registrado, se muestra un error genérico (sin confirmar existencia, por seguridad).
2. El código de recuperación tiene una validez de 10 minutos.
3. La nueva contraseña cumple los mismos criterios que en el registro y no puede repetir la anterior.
4. Al guardar, el sistema invalida el código y redirige al inicio de sesión.
**Prioridad:** Alta

---

## HU-08
**Requisito relacionado:** REQ-FUN-08
**Historia de usuario:** Como usuario, quiero acceder a un menú lateral con las opciones principales, para navegar fácilmente entre mi cuenta, mi progreso, los ajustes y las FAQ.
**Criterios de aceptación:**
1. El menú solo está disponible desde la pantalla principal, no dentro de los niveles de trivia.
2. Cada opción redirige a su pantalla correspondiente.
3. "Cerrar sesión" aparece resaltado en color de alerta y despliega la confirmación.
**Prioridad:** Media

---

## HU-09
**Requisito relacionado:** REQ-FUN-09
**Historia de usuario:** Como usuario, quiero ver una pantalla principal con un saludo personalizado y acceso directo a la trivia, para empezar a jugar rápidamente cada vez que abro la app.
**Criterios de aceptación:**
1. Solo se accede a esta pantalla tras iniciar sesión correctamente.
2. El botón de trivia abre la pantalla de niveles con los 20 niveles en orden.
3. El botón "Próximamente" es visible pero no ejecuta ninguna acción.
**Prioridad:** Alta

---

## HU-10
**Requisito relacionado:** REQ-FUN-10
**Historia de usuario:** Como usuario, quiero ver los 20 niveles de trivia y saber cuáles están disponibles, completados o bloqueados, para entender mi progreso y qué reto sigue.
**Criterios de aceptación:**
1. Al iniciar la app por primera vez, solo el nivel 1 está disponible.
2. Al completar un nivel, el siguiente cambia automáticamente a "disponible".
3. Los niveles bloqueados no responden a la interacción del usuario.
4. El estado de cada nivel se persiste y se sincroniza al iniciar sesión.
**Prioridad:** Alta

---

## HU-11
**Requisito relacionado:** REQ-FUN-11
**Historia de usuario:** Como usuario, quiero responder preguntas de trivia con un cronómetro y recibir retroalimentación inmediata, para mantenerme motivado, concentrado y aprender de mis errores sin frustrarme.
**Criterios de aceptación:**
1. Cada pregunta tiene un cronómetro regresivo de 10 segundos.
2. Tras 2 fallos consecutivos, el sistema muestra una pausa de "Estírate y respira" de 1 minuto (60 segundos).
3. Al superar un nivel, se muestra una ventana de felicitación y se avanza automáticamente en 3 segundos.
4. El número de reintentos queda registrado para consultarse en la pantalla de progreso.
**Prioridad:** Alta

---

## HU-12
**Requisito relacionado:** REQ-FUN-12
**Historia de usuario:** Como usuario, quiero ver mi progreso general (niveles completados, porcentaje y reintentos), para saber qué tanto he avanzado y sentirme motivado a continuar.
**Criterios de aceptación:**
1. El porcentaje se calcula como (niveles completados / 20) × 100.
2. La barra de progreso se actualiza al regresar a esta pantalla tras completar un nivel.
3. El total de reintentos refleja la suma acumulada de todas mis sesiones.
**Prioridad:** Media

---

## HU-13
**Requisito relacionado:** REQ-FUN-13
**Historia de usuario:** Como usuario, quiero personalizar el sonido, el tema visual y el tamaño del texto, para adaptar la aplicación a mis preferencias y necesidades.
**Criterios de aceptación:**
1. Las preferencias se aplican de inmediato y se guardan localmente en el dispositivo.
2. El modo claro/oscuro afecta a todas las pantallas de la aplicación.
3. "Sincronizar ahora" muestra un indicador de carga y confirma éxito o fallo al finalizar.
**Prioridad:** Media

---

## HU-14
**Requisito relacionado:** REQ-FUN-14
**Historia de usuario:** Como usuario, quiero consultar preguntas frecuentes y enviar comentarios a los desarrolladores, para resolver mis dudas sobre el uso de la app y compartir mis sugerencias.
**Criterios de aceptación:**
1. Se muestran al menos tres tarjetas de preguntas frecuentes, disponibles sin conexión.
2. El botón "Enviar" está deshabilitado si el campo de comentarios está vacío.
3. Al enviar un comentario (con conexión), se muestra una confirmación de recepción.
**Prioridad:** Baja

---

## HU-15
**Requisito relacionado:** REQ-FUN-01 / REQ-NF-02
**Historia de usuario:** Como acudiente, quiero que el registro de mi hijo o hija requiera mis datos de contacto y la verificación del correo, para tener certeza de que estoy autorizando y acompañando el uso seguro de la aplicación.
**Criterios de aceptación:**
1. El registro solicita nombre completo y cédula del acudiente en el paso 1.
2. La cuenta no se activa hasta verificar el código enviado al correo registrado.
3. Las contraseñas se almacenan mediante hashing con bcrypt; no se utiliza cifrado reversible.
**Prioridad:** Alta
