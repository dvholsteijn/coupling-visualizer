package nl.dvholsteijn.couplingvisualizer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Name;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.util.SupplierUtil;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CodebaseAnalyzer {

	public static final int MAX_THICKNESS = 6;

	private final Path exportTargetPath;

	// Store dependencies as a graph
	private final Graph<String, DefaultEdge> graph = new DirectedMultigraph<>(SupplierUtil.createStringSupplier(), SupplierUtil.createDefaultEdgeSupplier(), false);

	private final String svgTitle;

	private List<String> excludedPackages;

	public CodebaseAnalyzer(Path exportTargetPath, List<String> excludedPackages, String svgTitle) {
		this.exportTargetPath = exportTargetPath;
		this.excludedPackages = excludedPackages;
		this.svgTitle = svgTitle;
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.out.println("Usage: java CodebaseAnalyzer <codebasePath> <exportTargetPath> <excludedPackages> <svgTitle>");
			System.exit(1);
		}

		String codebasePath = args[0];
		Path exportTargetPath = Paths.get(args[1]);
		List<String> excludedPackages = Arrays.asList(args[2].split(","));
		String svgTitle = args[3];

		var codebaseAnalyzer = new CodebaseAnalyzer(exportTargetPath, excludedPackages, svgTitle);

		codebaseAnalyzer.initializeJavaParser();
		codebaseAnalyzer.analyzeCodebase(codebasePath);

		codebaseAnalyzer.exportGraphToDOT();
		codebaseAnalyzer.exportGraphToSVG();
	}

	private static void renderEdge(StringBuilder svgContent, Point2D sourcePosition, Point2D targetPosition, String sourceVertex, String targetVertex, int thickness) {
		svgContent.append(String.format("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"black\" opacity=\"0.25\" stroke-width=\"%d\" data-target=\"%s\">\n",
				(int) sourcePosition.getX(), (int) sourcePosition.getY(), (int) targetPosition.getX(), (int) targetPosition.getY(), thickness, targetVertex));
		svgContent.append(String.format("<title>%s -> %s</title>\n", sourceVertex, targetVertex));
		svgContent.append("</line>\n");
	}

	private static void renderVertex(String vertex, StringBuilder svgContent, int x, int y) {
		svgContent.append(String.format("<circle id=\"%s\" cx=\"%d\" cy=\"%d\" r=\"10\" stroke=\"black\" fill=\"transparent\" opacity=\"0.5\" onclick=\"changeColor(evt)\">\n", vertex, x, y));
		svgContent.append(String.format("<title>%s</title>\n", vertex));
		svgContent.append("</circle>\n");
	}

	private static String resolveTargetPackage(ImportDeclaration imp) {
		Name importName = imp.getName();
		String importedPackage = importName.getQualifier().map(Name::toString).orElse("default");

		// Check if the import is a static import
		if (imp.isStatic()) {
			// Extract the package part from the static import assuming the package part is all lowercase
			String[] parts = importedPackage.split("\\.");
			StringBuilder packagePart = new StringBuilder();
			for (String part : parts) {
				if (Character.isLowerCase(part.charAt(0))) {
					if (packagePart.length() > 0) {
						packagePart.append(".");
					}
					packagePart.append(part);
				} else {
					break;
				}
			}
			importedPackage = packagePart.toString();
		}
		return importedPackage;
	}

	public void exportGraphToSVG() {
		String timestamp = String.valueOf(System.currentTimeMillis());
		String sanitizedTitle = sanitizeFilename(svgTitle);
		Path outputPath = exportTargetPath.resolve("graph_circular_layout_" + sanitizedTitle + "_" + timestamp + ".svg");

		int centerX = 500;
		int centerY = 500;
		int radius = 400;
		double angleStep = 2 * Math.PI / graph.vertexSet().size();

		StringBuilder svgContent = new StringBuilder();
		svgContent.append("<svg width=\"1000\" height=\"1000\" xmlns=\"http://www.w3.org/2000/svg\">\n");
		renderTitle(svgContent);
		svgContent.append("<script type=\"text/ecmascript\">\n");
		svgContent.append("<![CDATA[\n");
		svgContent.append("function changeColor(evt) {\n");
		svgContent.append("  var elements = document.getElementsByTagName('circle');\n");
		svgContent.append("  for (var i = 0; i < elements.length; i++) {\n");
		svgContent.append("    elements[i].setAttribute('fill', 'transparent');\n");
		svgContent.append("  }\n");
		svgContent.append("  var element = evt.target;\n");
		svgContent.append("  element.setAttribute('fill', 'red');\n");
		svgContent.append("  var edges = document.getElementsByTagName('line');\n");
		svgContent.append("  for (var i = 0; i < edges.length; i++) {\n");
		svgContent.append("    if (edges[i].getAttribute('data-target') === element.id) {\n");
		svgContent.append("      edges[i].setAttribute('stroke', 'red');\n");
		svgContent.append("      edges[i].setAttribute('opacity', '1');\n");
		svgContent.append("    } else {\n");
		svgContent.append("      edges[i].setAttribute('stroke', 'black');\n");
		svgContent.append("      edges[i].setAttribute('opacity', '0.25');\n");
		svgContent.append("    }\n");
		svgContent.append("  }\n");
		svgContent.append("}\n");
		svgContent.append("]]>\n");
		svgContent.append("</script>\n");

		Map<String, Point2D> positions = new HashMap<>();
		int i = 0;
		for (String vertex : graph.vertexSet()) {
			double angle = i * angleStep;
			int x = (int) (centerX + radius * Math.cos(angle));
			int y = (int) (centerY + radius * Math.sin(angle));
			positions.put(vertex, new Point2D(x, y));

			renderVertex(vertex, svgContent, x, y);
			i++;
		}

		Set<String> renderedEdges = new HashSet<>();
		for (DefaultEdge edge : graph.edgeSet()) {
			String sourceVertex = graph.getEdgeSource(edge);
			String targetVertex = graph.getEdgeTarget(edge);
			String edgeKey = sourceVertex + "->" + targetVertex;

			if (!renderedEdges.contains(edgeKey)) {
				Point2D sourcePosition = positions.get(sourceVertex);
				Point2D targetPosition = positions.get(targetVertex);

				// Calculate the thickness based on the multiplicity of edges
				int thickness = graph.getAllEdges(sourceVertex, targetVertex).size();

				renderEdge(svgContent, sourcePosition, targetPosition, sourceVertex, targetVertex, thickness > MAX_THICKNESS ? MAX_THICKNESS : thickness);
				renderedEdges.add(edgeKey);
			}
		}

		svgContent.append("</svg>");

		try (FileWriter writer = new FileWriter(outputPath.toFile())) {
			System.out.println("Writing svg export to: " + outputPath);
			writer.write(svgContent.toString());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void analyzeCodebase(String codebasePath) throws IOException {
		// Traverse the codebase directory and process each Java file
		Files.walk(Paths.get(codebasePath))
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.forEach(this::processJavaFile);
	}

	private void exportGraphToDOT() {

		DOTExporter<String, DefaultEdge> exporter =
				new DOTExporter<>(v -> v.replace('.', '_'));
		exporter.setVertexAttributeProvider((v) -> {
			Map<String, Attribute> map = new LinkedHashMap<>();
			map.put("label", DefaultAttribute.createAttribute(v.toString()));
			return map;
		});
		Writer writer = new StringWriter();
		exporter.exportGraph(graph, writer);
		System.out.println(writer.toString());
	}

	private void initializeJavaParser() {

		ParserConfiguration configuration = new ParserConfiguration();

		configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

		StaticJavaParser.setConfiguration(configuration);
	}

	private void processJavaFile(Path javaFilePath) {
		try {
			// Parse the Java file
			CompilationUnit cu = StaticJavaParser.parse(new FileReader(javaFilePath.toFile()));

			// Get the package name
			String packageName = cu.getPackageDeclaration().map(pd -> pd.getName().toString()).orElse("default");

			// Add the package as a node in the graph
			graph.addVertex(packageName);

			// Process import statements to determine efferent coupling
			List<ImportDeclaration> imports = cu.findAll(ImportDeclaration.class);
			for (ImportDeclaration imp : imports) {
				final String importedPackage = resolveTargetPackage(imp);

				// Skip importedPackage that are excluded
				if (excludedPackages.stream().noneMatch(excludedPackage -> importedPackage.startsWith(excludedPackage))) {

					// the imported package is added as a node if not already present
					System.out.println("Adding edge from " + packageName + " to " + importedPackage);
					graph.addVertex(importedPackage);

					try {
						// Add an edge from the current package to the imported package
						graph.addEdge(packageName, importedPackage);
					} catch (IllegalArgumentException e) {
						System.out.println("Loop detected! Skipping edge from " + packageName + " to " + importedPackage);
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void renderTitle(StringBuilder svgContent) {
		svgContent.append(String.format("<text x=\"50%%\" y=\"30\" text-anchor=\"middle\" font-size=\"24\" font-family=\"Arial\" fill=\"black\">%s</text>\n", svgTitle));
	}

	private String sanitizeFilename(String input) {
		String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
		String sanitized = normalized.replaceAll("[^\\p{ASCII}]", "");
		sanitized = sanitized.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		return sanitized;
	}

	private static class Point2D {
		private final double x;

		private final double y;

		public Point2D(double x, double y) {
			this.x = x;
			this.y = y;
		}

		public double getX() {
			return x;
		}

		public double getY() {
			return y;
		}
	}
}
