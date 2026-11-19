# Clean Deals

App de Android para ver las ofertas de juegos de Xbox en México (MXN), sin anuncios, sin rastreadores y sin cuentas.

- Único permiso: `INTERNET`.
- Sin Google Play Services, sin analíticas, sin librerías de terceros para red o imágenes.
- La lista de deseos se guarda solo en tu teléfono.
- Al tocar un juego se abre su página en la tienda de Xbox.

## Cómo obtener el APK (con GitHub)

1. Crea un repositorio nuevo en GitHub (puede ser privado).
2. Desde esta carpeta:

   ```bash
   git init -b main
   git add .
   git commit -m "Clean Deals"
   git remote add origin https://github.com/TU_USUARIO/CleanDeals.git
   git push -u origin main
   ```

   (Usa `git add .` para que se incluya la carpeta oculta `.github`; si subes archivos arrastrándolos en la web, esa carpeta suele quedarse fuera.)
3. En GitHub abre la pestaña **Actions** → **Compilar APK**. Tarda unos minutos.
4. Entra a la ejecución terminada, baja a **Artifacts** y descarga `CleanDeals-apk`.
5. Descomprime e instala el `.apk` en tu teléfono (hay que permitir "instalar apps desconocidas" para el navegador o el gestor de archivos).

Si falla la compilación, copia el error de la pestaña Actions y se corrige.

## Si dejan de aparecer ofertas

Los datos salen de dos endpoints del catálogo público de Microsoft Store, no de una API oficial documentada. Toda esa lógica está en `app/src/main/java/app/cleandeals/Api.kt`; si Microsoft los cambia, solo hay que ajustar ese archivo.

## Otro país

En `Api.kt` cambia `MARKET` y `LANGUAGE` (por ejemplo `US` y `en-US`), y la URL de la tienda en `openStore` dentro de `MainActivity.kt`.

## Nota sobre la firma

El APK se firma con la llave de depuración para que se pueda instalar sin trámites. Es para uso personal; no sirve para subirlo a Google Play.
