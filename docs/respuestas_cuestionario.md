# Cuestionario de Análisis Técnico y Reflexión DevOps

**Taller Semana 10 — Pipeline CI/CD en Jenkins (Test, Sonar, Nexus y Despliegue)**
Ingeniería de Software V · Universidad Icesi · 2026-2

---

## 1. Gobernanza y compuertas de bloqueo

**¿Por qué es una pésima práctica permitir que la Publicación a Nexus o el Despliegue se ejecuten antes de evaluar el Quality Gate?**

El orden de las etapas define qué puede salir del pipeline. Si la publicación o el despliegue ocurren antes del Quality Gate, el análisis de SonarQube pasa a ser solo informativo: el artefacto ya salió cuando llega el veredicto. La compuerta deja de bloquear algo.

**Impacto técnico:**

- **Contaminación del repositorio inmutable.** Nexus guarda los *releases* como fuente de verdad. Un JAR con vulnerabilidades publicado en `maven-releases` queda disponible para cualquier equipo o servicio que lo consuma como dependencia. Además, como el repositorio de releases bloquea el *redeploy*, la versión defectuosa no se puede sobrescribir: hay que publicar una versión nueva y rastrear quién consumió la anterior.
- **Exposición en ejecución.** En mi taller, los dos defectos inyectados eran un generador de SKU con `java.util.Random` (CWE-330, valores predecibles) y un `Optional.get()` sin validar (CWE-476, puede tumbar la petición con `NoSuchElementException`). Desplegados, el primero permite predecir identificadores y el segundo genera errores 500 ante IDs inexistentes.
- **Pérdida de trazabilidad.** Si cualquier build termina publicado, "estar en Nexus" deja de significar "pasó las validaciones", y la auditoría pierde valor.

**Impacto de negocio:** incidentes en producción, costo de *rollback* y de corrección urgente, riesgo de explotación de vulnerabilidades y pérdida de confianza de clientes. Corregir un defecto en producción es órdenes de magnitud más caro que detenerlo en el pipeline.

En mi Jenkinsfile, las etapas 3 y 4 van después de `2.1. Quality Gate Enforcement` y además solo corren en `main` (`when { branch 'main' }`). Cuando el gate salió en rojo por los defectos, Jenkins marcó la publicación, la imagen y el despliegue como *skipped due to earlier failure(s)*: nada llegó a Nexus ni al puerto 8085.

---

## 2. Mecanismo de webhooks vs. polling activo

**¿Cómo interactúan `report-task.txt`, `ceTaskId`, `waitForQualityGate()` y `/sonarqube-webhook/`?**

1. En la etapa `2. SonarQube SAST Scan`, `mvn sonar:sonar` (dentro de `withSonarQubeEnv`) analiza el código y **sube** el reporte a SonarQube. El análisis no termina ahí: SonarQube lo encola en su *Compute Engine* para procesarlo de forma asíncrona.
2. Al subirlo, SonarQube devuelve un identificador de tarea, el **`ceTaskId`**. El scanner lo guarda junto con la URL del servidor en **`target/sonar/report-task.txt`**. En mi log se ve como `More about the report processing at .../api/ce/task?id=...`.
3. En la etapa siguiente, **`waitForQualityGate()`** lee ese `ceTaskId` (el plugin lo conserva del paso anterior) y **suspende** el pipeline sin ocupar un ejecutor activamente.
4. Cuando el Compute Engine termina y calcula el Quality Gate, SonarQube hace un **POST** al webhook configurado, `http://<jenkins>/sonarqube-webhook/`, con el `ceTaskId` y el estado (`OK` o `ERROR`).
5. El plugin de Jenkins recibe ese POST en el endpoint **`/sonarqube-webhook/`**, lo asocia con el build que esperaba ese `ceTaskId` y lo despierta con el veredicto. Si el estado no es `OK`, mi pipeline ejecuta `error` y aborta.

**¿Por qué es superior al polling periódico?**

- **Eficiencia:** con *polling*, cada build en espera consulta la API cada pocos segundos. En un clúster con muchos pipelines concurrentes, eso multiplica peticiones inútiles contra SonarQube y mantiene hilos ocupados en Jenkins. Con el webhook hay una sola notificación por análisis.
- **Latencia:** el build se reanuda en cuanto llega el evento, sin esperar al siguiente intervalo de consulta.
- **Escalabilidad y desacoplamiento:** SonarQube no necesita saber cuántos builds esperan. Publica un evento y el consumidor reacciona.

Lo comprobé en la práctica: en mi primera ejecución escribí mal la URL del webhook (`host.docker.internar`). El log de SonarQube mostró `Failed to send webhook ... Name or service not known`, y Jenkins se quedó esperando hasta cortar por el `timeout`. Al corregir la URL a `http://host.docker.internal:9182/sonarqube-webhook/`, el Quality Gate respondió en segundos.

---

## 3. Resiliencia y *circuit breaking* con `timeout`

**Si ya hay un webhook reactivo, ¿por qué es obligatorio rodear la llamada con `timeout(time: 5, unit: 'MINUTES')`?**

Porque el webhook es una dependencia externa que puede fallar, y sin un límite el pipeline depende ciegamente de que el evento llegue. El `timeout` funciona como *circuit breaker*: si la notificación no llega en un tiempo razonable, el pipeline falla de forma controlada y libera los recursos.

**¿Qué pasaría sin ese límite ante un fallo de red o un reinicio de SonarQube?**

- Si SonarQube se reinicia mientras procesa la tarea, o la red entre los dos servidores falla, el POST al webhook nunca llega. SonarQube no reintenta indefinidamente.
- El build quedaría suspendido para siempre en `waitForQualityGate()`: un **build zombie**.
- Cada zombie retiene su ejecutor, su workspace en disco y, con `disableConcurrentBuilds` o colas por rama, bloquea los builds siguientes de esa misma rama.
- Con varios equipos, los ejecutores se agotan y todo el servidor de CI deja de procesar trabajos hasta que alguien aborte los builds a mano.

Lo viví en mi primer build: con la URL del webhook mal escrita, la etapa del Quality Gate quedó en *paused for 4min 59s* y el `timeout` la abortó. Sin ese límite, ese build seguiría colgado. Además, el bloque `options { timeout(time: 20, unit: 'MINUTES') }` agrega un límite global a todo el pipeline como segunda capa de protección.

---

## 4. Falso positivo en despliegues y el rol del healthcheck

**¿Por qué no basta con que `docker run` retorne código 0?**

`docker run -d` retorna 0 en cuanto el contenedor **se crea y arranca su proceso**, no cuando la aplicación está lista para atender peticiones. Spring Boot tarda varios segundos en inicializar el contexto; durante ese tiempo el contenedor está "corriendo", pero el servicio no responde. Si la aplicación falla al iniciar, el contenedor muere segundos después, cuando el comando ya devolvió éxito.

**Fallos de Spring Boot que solo aparecen después del arranque:**

- Errores en la creación del **contexto de Spring**: beans mal configurados, dependencias circulares, propiedades faltantes en `application.yml`.
- Fallos de conexión a la **base de datos** o de migraciones de esquema.
- **Puerto ocupado** dentro del contenedor o configuración de servidor inválida.
- Falta de memoria de la JVM (`OutOfMemoryError`) bajo los límites del contenedor.
- Errores en *initializers*, como un `DataInitializer` que carga datos al arrancar y lanza una excepción.

**Cómo protege el smoke test con `curl`:**

El pipeline consulta activamente `GET /api/products` hasta 10 veces, cada 3 segundos, y solo declara éxito si recibe una respuesta HTTP 2xx (`curl -f`). Así verifica que la aplicación arrancó, cargó su contexto, accede a sus datos y atiende peticiones reales. Si no responde en ~30 segundos, la etapa falla con `exit 1` y el release no se certifica.

En mi taller vi el caso contrario, un falso negativo, que muestra lo mismo desde otro ángulo: en un build el `docker run` retornó 0 y la aplicación estaba arriba, pero el smoke test falló por dos errores del script. El `sh` de Jenkins es `dash` y no expande `{1..10}`, así que el ciclo dio una sola vuelta, y consultaba `localhost`, que dentro del contenedor de Jenkins es Jenkins y no el host. Lo corregí con `$(seq 1 10)` y `host.docker.internal`. La lección: el resultado de un comando de infraestructura no basta como evidencia; hay que verificar el comportamiento observable del servicio, y también validar el propio script de verificación.
