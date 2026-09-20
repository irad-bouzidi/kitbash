package dev.kitbash.core.patch;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A real XML tree for {@code pom.xml}, as §10 requires.
 *
 * <p>The JDK ships a DOM parser, so this is the one structured format that needs no dependency.
 * Two things are worth stating about it:
 *
 * <ul>
 *   <li><b>External entities are off.</b> A pom is a file a recipe wrote, but a parser that
 *       resolves entities is an XXE waiting for the day one is not — and turning them off costs
 *       three lines (§13).
 *   <li><b>Whitespace text nodes are stripped before re-serialising.</b> Without that, the
 *       transformer's indentation stacks on top of the existing one and every patched pom grows a
 *       staircase of blank lines. Re-indenting from scratch is what keeps the output diff-readable.
 * </ul>
 */
final class MavenPomFile {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private MavenPomFile() {}

    /** Adds a {@code <dependency>}, creating {@code <dependencies>} if the pom has none. */
    static String addDependency(String source, String coordinate, String scope) {
        Document pom = parse(source);
        Element project = pom.getDocumentElement();
        Element dependencies = child(project, "dependencies").orElseGet(() -> {
            Element created = pom.createElement("dependencies");
            project.appendChild(created);
            return created;
        });

        String[] parts = coordinate.split(":");
        String groupId = parts[0];
        String artifactId = parts.length > 1 ? parts[1] : "";
        String version = parts.length > 2 ? parts[2] : null;

        if (declares(dependencies, groupId, artifactId)) {
            return source;
        }

        Element dependency = pom.createElement("dependency");
        dependency.appendChild(element(pom, "groupId", groupId));
        dependency.appendChild(element(pom, "artifactId", artifactId));
        if (version != null) {
            dependency.appendChild(element(pom, "version", version));
        }
        // Maven's default scope is `compile`; writing it out adds noise to every entry.
        if (scope != null && !scope.isBlank() && !"compile".equals(scope)) {
            dependency.appendChild(element(pom, "scope", scope));
        }
        dependencies.appendChild(dependency);

        return write(pom);
    }

    private static boolean declares(Element dependencies, String groupId, String artifactId) {
        NodeList declared = dependencies.getElementsByTagName("dependency");
        for (int i = 0; i < declared.getLength(); i++) {
            Element dependency = (Element) declared.item(i);
            boolean sameGroup = child(dependency, "groupId")
                    .map(Node::getTextContent)
                    .map(String::strip)
                    .filter(groupId::equals)
                    .isPresent();
            boolean sameArtifact = child(dependency, "artifactId")
                    .map(Node::getTextContent)
                    .map(String::strip)
                    .filter(artifactId::equals)
                    .isPresent();
            if (sameGroup && sameArtifact) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Element> child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static Element element(Document pom, String name, String text) {
        Element element = pom.createElement(name);
        element.setTextContent(text);
        return element;
    }

    private static Document parse(String source) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
            stripWhitespace(document.getDocumentElement());
            return document;
        } catch (ParserConfigurationException | SAXException e) {
            throw new Documents.DocumentParseException("pom.xml is not well-formed XML: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read pom.xml from memory", e);
        }
    }

    /** Drops indentation text nodes so the transformer can lay the document out afresh. */
    private static void stripWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespace(child);
            }
        }
    }

    private static String write(Document pom) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            // The transformer's own declaration carries `standalone="no"`, which no pom in the
            // world is written with. Suppressing it and writing the conventional one keeps a
            // patched pom looking like a pom rather than like something a tool chewed.
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(pom), new StreamResult(out));
            String xml = DECLARATION + out.toString();
            return xml.endsWith("\n") ? xml : xml + "\n";
        } catch (TransformerException e) {
            throw new IllegalStateException("Could not write pom.xml", e);
        }
    }
}
