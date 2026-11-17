import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageDownloader {

    private static final int TIMEOUT_CONNECT = 10000;
    private static final int TIMEOUT_READ = 15000;
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024; // 10 MB

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: java ImageDownloader <URL> [directorio_salida]");
            System.exit(1);
        }

        String urlString = args[0];
        Path outputDir = Path.of(args.length == 2 ? args[1] : "imagenes");

        System.out.println("ImageDownloader");
        System.out.println("---------------------------------------");
        System.out.println("Conectando con: " + urlString);

        try {
            URL baseUrl = validarURL(urlString);

            // Crear directorio de salida
            Files.createDirectories(outputDir);

            // Obtener HTML
            String html = obtenerHTML(baseUrl);
            System.out.println("\nAnalizando documento HTML...");

            // Extraer referencias de imágenes únicas
            Set<String> srcReferences = extraerImagenes(html);
            System.out.println("Imágenes encontradas: " + srcReferences.size() + "\n");

            Set<URL> resolvedImageUrls = new HashSet<>();
            for (String src : srcReferences) {
                try {
                    URL resolvedUrl = resolverImagen(baseUrl, src);
                    if (resolvedUrl != null) {
                        resolvedImageUrls.add(resolvedUrl);
                    }
                } catch (Exception e) {
                    // Ignorar URLs individuales malformadas
                }
            }

            int totalFound = resolvedImageUrls.size();
            int successfulDownloads = 0;
            int failedDownloads = 0;
            int counter = 1;

            for (URL imageUrl : resolvedImageUrls) {
                String fileNameFormatted = String.format("imagen_%03d", counter);
                boolean success = descargarImagenBinaria(imageUrl, outputDir, fileNameFormatted, counter, totalFound);
                if (success) {
                    successfulDownloads++;
                } else {
                    failedDownloads++;
                }
                counter++;
            }

            System.out.println("\n---------------------------------------");
            System.out.println("Descargas exitosas: " + successfulDownloads);
            System.out.println("Descargas fallidas:  " + failedDownloads);
            System.out.println("Directorio:          " + outputDir.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("Error en la ejecución: " + e.getMessage());
            System.exit(1);
        }
    }

    public static URL validarURL(String argumento) throws Exception {
        URI uri = URI.create(argumento);
        URL url = uri.toURL();
        String protocol = url.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("Solo se admiten protocolos HTTP y HTTPS.");
        }
        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("La URL debe contener un servidor válido.");
        }
        return url;
    }

    public static String obtenerHTML(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_CONNECT);
        connection.setReadTimeout(TIMEOUT_READ);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Compatible; ImageDownloader/1.0)");

        int codigo = connection.getResponseCode();
        System.out.println("Código HTTP: " + codigo);

        if (codigo != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("El servidor respondió con el código HTTP: " + codigo);
        }

        String contentType = connection.getContentType();
        System.out.println("Tipo de contenido: " + (contentType != null ? contentType : "Desconocido"));

        StringBuilder content = new StringBuilder();
        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                content.append(linea).append("\n");
            }
        } finally {
            connection.disconnect();
        }
        return content.toString();
    }

    public static Set<String> extraerImagenes(String html) {
        Set<String> srcs = new HashSet<>();
        Pattern imgPattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern srcPattern = Pattern.compile("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);

        Matcher imgMatcher = imgPattern.matcher(html);
        while (imgMatcher.find()) {
            String etiqueta = imgMatcher.group();
            Matcher srcMatcher = srcPattern.matcher(etiqueta);
            if (srcMatcher.find()) {
                String src = srcMatcher.group(2).trim();
                if (!src.isEmpty() && !src.startsWith("data:") && !src.startsWith("javascript:")) {
                    srcs.add(src);
                }
            }
        }
        return srcs;
    }

    public static URL resolverImagen(URL base, String referencia) throws Exception {
        URL resolved = new URL(base, referencia);
        String protocol = resolved.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            return null;
        }
        return resolved;
    }

    public static boolean descargarImagenBinaria(URL urlImagen, Path directorio, String baseName, int current, int total) {
        String urlString = urlImagen.toString();
        String shortName = urlString.substring(urlString.lastIndexOf('/') + 1);
        if (shortName.isEmpty() || shortName.contains("?")) {
            shortName = baseName + ".jpg";
        } else {
            shortName = baseName + "_" + shortName.replaceAll("[^a-zA-Z0-9.-]", "_");
        }

        Path destino = directorio.resolve(shortName);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) urlImagen.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_CONNECT);
            connection.setReadTimeout(TIMEOUT_READ);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Compatible; ImageDownloader/1.0)");

            int codigo = connection.getResponseCode();
            if (codigo != HttpURLConnection.HTTP_OK) {
                System.out.printf("[%d/%d] %s ........... ERROR (HTTP %d)%n", current, total, shortName, codigo);
                return false;
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase().startsWith("image/")) {
                System.out.printf("[%d/%d] %s ........... OMITIDO (No es imagen: %s)%n", current, total, shortName, contentType);
                return false;
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_SIZE) {
                System.out.printf("[%d/%d] %s ........... OMITIDO (Excede límite de tamaño)%n", current, total, shortName);
                return false;
            }

            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, destino, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf("[%d/%d] %s ............... OK%n", current, total, shortName);
                return true;
            }

        } catch (IOException e) {
            System.out.printf("[%d/%d] %s ........... ERROR (%s)%n", current, total, shortName, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}