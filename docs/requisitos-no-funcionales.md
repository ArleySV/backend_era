# ERA — Requisitos No Funcionales

> Documentación oficial del sistema ERA. Ver [`../CLAUDE.md`](../CLAUDE.md) para las
> reglas permanentes del proyecto y la matriz de relevancia para el backend.

---

## REQ-NF-01 – Rendimiento

**ID:** REQ-NF-01
**Categoría:** Rendimiento
**Descripción:** La aplicación debe responder a las acciones del usuario en menos de 3 segundos. El tiempo de carga de cada pantalla no debe superar los 2 segundos. Se esperan tiempos de respuesta no superiores a 3 segundos entre peticiones, por ejemplo: ingresar a un nivel de trivia, cargar una pregunta o navegar entre secciones.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo

---

## REQ-NF-02 – Seguridad

**ID:** REQ-NF-02
**Categoría:** Seguridad
**Descripción:** El sistema garantizará el inicio de sesión mediante nombre de usuario y contraseña según lo definido en REQ-FUN-02. La contraseña se almacenará mediante un algoritmo de hashing de un solo sentido, específicamente bcrypt (con factor de costo adecuado según buenas prácticas vigentes). No se utilizará cifrado reversible (como AES) para el almacenamiento de contraseñas, dado que un algoritmo reversible expondría las contraseñas en texto plano ante cualquier compromiso de la clave de cifrado. En caso de olvido de contraseña, el sistema enviará un correo de recuperación al correo registrado según REQ-FUN-07. Tras 5 intentos fallidos consecutivos de inicio de sesión, el sistema bloqueará temporalmente el acceso durante 2 minutos.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo

---

## REQ-NF-03 – Usabilidad

**ID:** REQ-NF-03
**Categoría:** Usabilidad
**Descripción:** La aplicación debe tener una interfaz sencilla, intuitiva y atractiva orientada a niños de primaria (entre 7 a 11 años). Los contenidos estarán organizados por niveles, simulando la estructura de un juego móvil. La navegación será clara, con botones simples y símbolos fácilmente reconocibles. La app permitirá cambiar entre al menos dos temas visuales (claro y oscuro). Se incluirá un campo para comentarios y sugerencias por parte del usuario.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo – Formulario recolección de requerimientos

---

## REQ-NF-04 – Confiabilidad

**ID:** REQ-NF-04
**Categoría:** Confiabilidad
**Descripción:** La aplicación debe garantizar un funcionamiento continuo y estable durante las sesiones de uso. El sistema debe tener tolerancia a fallos básicos, por ejemplo: cierres inesperados o pérdida de progreso del usuario. En condiciones normales, el sistema debe estar disponible al menos el 95% del tiempo.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo, buenas prácticas de desarrollo

---

## REQ-NF-05 – Mantenibilidad

**ID:** REQ-NF-05
**Categoría:** Mantenibilidad
**Descripción:** Las actualizaciones se aplicarán automáticamente siempre que el dispositivo esté conectado a una red Wi-Fi y no afectarán la configuración ni los datos almacenados del usuario.

**Prioridad:** Media
**Fuente:** Reunión integrantes del equipo

---

## REQ-NF-06 – Portabilidad

**ID:** REQ-NF-06
**Categoría:** Portabilidad
**Descripción:** La aplicación debe ser compatible con dispositivos móviles Android desde la versión 8.0 en adelante. Su diseño y funcionalidad estarán optimizados para este sistema operativo, según los resultados de la encuesta a los usuarios potenciales.

**Prioridad:** Alta
**Fuente:** Reunión integrantes del equipo – Cuestionario recolección de requerimientos
