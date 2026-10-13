// Fake CardDAV server for trying Rolodex Light sync on the emulator: java tool/scripts/MockCardDav.java
// Sign in with any email/password and server http://10.0.2.2:5233/
// Like mailbox.org (OX), it keeps only two mobile numbers per card.
// Change things "elsewhere": curl -X PUT --data-binary @card.vcf localhost:5233/admin/<book>/<name>.vcf
//                            curl -X DELETE localhost:5233/admin/<book>/<name>.vcf ; curl localhost:5233/admin/dump
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MockCardDav {
    static final String HOME = "/dav/addressbooks/me/";
    static final Map<String, Map<String, String[]>> books = new LinkedHashMap<>(); // book -> href -> {etag, vcf}
    static final Set<String> readOnly = new HashSet<>();
    static int tag = 1;

    public static void main(String[] a) throws Exception {
        books.put(HOME + "contacts/", new LinkedHashMap<>());
        books.put(HOME + "friends/", new LinkedHashMap<>());
        books.put(HOME + "shared/", new LinkedHashMap<>());
        readOnly.add(HOME + "shared/");
        add("contacts/", "ada", card("ada", "Ada Example", "Example;Ada;;;", "TEL;TYPE=CELL:+1 555 0100", "EMAIL;TYPE=HOME:ada@example.org"));
        add("contacts/", "bert", card("bert", "Bert Sample", "Sample;Bert;;;", "TEL;TYPE=WORK:+1 555 0111", "ORG:Sample Co", "X-SOMETHING:kept by server", "PHOTO;ENCODING=b;TYPE=JPEG:AAAA"));
        add("friends/", "cleo", card("cleo", "Cleo Test", "Test;Cleo;;;", "TEL;TYPE=CELL:+1 555 0122", "BDAY:1990-04-12"));
        add("shared/", "dina", card("dina", "Dina Readonly", "Readonly;Dina;;;", "TEL;TYPE=HOME:+1 555 0133"));

        HttpServer s = HttpServer.create(new InetSocketAddress(5233), 0);
        s.createContext("/", MockCardDav::handle);
        s.start();
        System.out.println("Mock CardDAV on :5233");
    }

    static String card(String uid, String fn, String n, String... more) {
        StringBuilder b = new StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + uid + "\r\nFN:" + fn + "\r\nN:" + n + "\r\n");
        for (String m : more) b.append(m).append("\r\n");
        return b.append("END:VCARD\r\n").toString();
    }

    static void add(String book, String name, String vcf) {
        books.get(HOME + book).put(HOME + book + name + ".vcf", new String[]{"\"" + (tag++) + "\"", vcf});
    }

    /** Two mobile numbers at most, like OX. */
    static String store(String vcf) {
        StringBuilder out = new StringBuilder();
        int cells = 0;
        for (String line : vcf.replace("\r\n", "\n").split("\n")) {
            String u = line.toUpperCase();
            boolean cell = (u.startsWith("TEL") || u.matches("^ITEM\\d+\\.TEL.*")) && u.substring(0, Math.max(0, u.indexOf(':'))).contains("CELL");
            if (cell && ++cells > 2) continue;
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    static String bookOf(String p) {
        return books.keySet().stream().filter(p::startsWith).findFirst().orElse(null);
    }

    static String ctag(String b) {
        return "" + books.get(b).values().stream().map(v -> v[0]).reduce("", String::concat).hashCode();
    }

    static synchronized void handle(HttpExchange x) throws IOException {
        String m = x.getRequestMethod(), p = URLDecoder.decode(x.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println(m + " " + p + " " + Optional.ofNullable(x.getRequestHeaders().getFirst("If-Match")).orElse("")
            + Optional.ofNullable(x.getRequestHeaders().getFirst("If-None-Match")).map(v -> " If-None-Match " + v).orElse(""));
        if (p.startsWith("/admin/")) {
            admin(x, m, p.substring("/admin/".length()), body);
            return;
        }
        String book = bookOf(p);
        switch (m) {
            case "PROPFIND" -> {
                StringBuilder r = new StringBuilder();
                if (body.contains("current-user-principal")) r.append(resp(p, "<d:current-user-principal><d:href>/principals/me/</d:href></d:current-user-principal>"));
                else if (body.contains("addressbook-home-set")) r.append(resp(p, "<c:addressbook-home-set><d:href>" + HOME + "</d:href></c:addressbook-home-set>"));
                else if (p.equals(HOME)) {
                    r.append(resp(HOME, "<d:resourcetype><d:collection/></d:resourcetype>"));
                    for (String b : books.keySet()) {
                        String name = b.split("/")[4];
                        r.append(resp(b, "<d:resourcetype><d:collection/><c:addressbook/></d:resourcetype><d:displayname>"
                            + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "</d:displayname><cs:getctag>" + ctag(b) + "</cs:getctag>"
                            + "<d:current-user-privilege-set><d:privilege><d:read/></d:privilege>"
                            + (readOnly.contains(b) ? "" : "<d:privilege><d:write/></d:privilege>") + "</d:current-user-privilege-set>"));
                    }
                } else if (book != null && p.equals(book)) {
                    r.append(resp(book, "<d:resourcetype><d:collection/><c:addressbook/></d:resourcetype>"));
                    for (var e : books.get(book).entrySet()) r.append(resp(e.getKey(), "<d:resourcetype/><d:getetag>" + e.getValue()[0] + "</d:getetag>"));
                } else {
                    r.append(resp(p, "<d:resourcetype><d:collection/></d:resourcetype>"));
                }
                multi(x, r.toString());
            }
            case "REPORT" -> {
                StringBuilder r = new StringBuilder();
                for (var e : books.get(book).entrySet()) {
                    if (!body.contains(e.getKey())) continue;
                    r.append(resp(e.getKey(), "<d:getetag>" + e.getValue()[0] + "</d:getetag><c:address-data>" + xml(e.getValue()[1]) + "</c:address-data>"));
                }
                multi(x, r.toString());
            }
            case "GET" -> {
                String[] cur = book == null ? null : books.get(book).get(p);
                if (cur == null) { send(x, 404, ""); return; }
                x.getResponseHeaders().set("ETag", cur[0]);
                send(x, 200, cur[1]);
            }
            case "PUT" -> {
                if (book == null) { send(x, 404, ""); return; }
                if (readOnly.contains(book)) { send(x, 403, ""); return; }
                String[] cur = books.get(book).get(p);
                String im = x.getRequestHeaders().getFirst("If-Match");
                String inm = x.getRequestHeaders().getFirst("If-None-Match");
                if ((inm != null && cur != null) || (im != null && (cur == null || !cur[0].equals(im)))) { send(x, 412, ""); return; }
                String etag = "\"" + (tag++) + "\"";
                books.get(book).put(p, new String[]{etag, store(body)});
                // No ETag on purpose now and then, like some servers.
                if (tag % 2 == 0) x.getResponseHeaders().set("ETag", etag);
                send(x, cur == null ? 201 : 204, "");
            }
            case "DELETE" -> {
                String[] cur = book == null ? null : books.get(book).get(p);
                if (cur == null) { send(x, 404, ""); return; }
                if (readOnly.contains(book)) { send(x, 403, ""); return; }
                String im = x.getRequestHeaders().getFirst("If-Match");
                if (im != null && !cur[0].equals(im)) { send(x, 412, ""); return; }
                books.get(book).remove(p);
                send(x, 204, "");
            }
            default -> send(x, 405, "");
        }
    }

    static void admin(HttpExchange x, String m, String rest, String body) throws IOException {
        if (rest.equals("dump")) {
            StringBuilder b = new StringBuilder();
            books.forEach((k, v) -> v.forEach((h, c) -> b.append("== ").append(h).append(" ").append(c[0]).append("\n").append(c[1])));
            send(x, 200, b.toString());
            return;
        }
        String p = HOME + rest, book = bookOf(p);
        if (book == null) { send(x, 404, "no book"); return; }
        if (m.equals("DELETE")) books.get(book).remove(p);
        else books.get(book).put(p, new String[]{"\"" + (tag++) + "\"", body.replace("\r\n", "\n").replace("\n", "\r\n")});
        send(x, 200, "ok\n");
    }

    static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    static String resp(String href, String props) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    static void multi(HttpExchange x, String inner) throws IOException {
        x.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        send(x, 207, "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\" xmlns:cs=\"http://calendarserver.org/ns/\">" + inner + "</d:multistatus>");
    }

    static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream o = x.getResponseBody()) { o.write(b); }
        x.close();
    }
}
