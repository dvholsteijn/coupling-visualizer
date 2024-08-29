package nl.dvholsteijn.couplingvisualizer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Name;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.swing.mxGraphComponent;
import org.jgrapht.Graph;
import org.jgrapht.alg.drawing.CircularLayoutAlgorithm2D;
import org.jgrapht.alg.drawing.model.Box2D;
import org.jgrapht.alg.drawing.model.LayoutModel2D;
import org.jgrapht.alg.drawing.model.MapLayoutModel2D;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.util.SupplierUtil;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CodebaseAnalyzer {

	private final Path exportTargetPath;

	// Store dependencies as a graph
	private final Graph<String, DefaultEdge> graph = new DirectedMultigraph<>(SupplierUtil.createStringSupplier(), SupplierUtil.createDefaultEdgeSupplier(), false);

	private List<String> excludedPackages;

	public CodebaseAnalyzer(Path exportTargetPath, List<String> excludedPackages) {
		this.exportTargetPath = exportTargetPath;
		this.excludedPackages = excludedPackages;
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 3) {
			System.out.println("Usage: java CodebaseAnalyzer <codebasePath> <exportTargetPath> <excludedPackages>");
			System.exit(1);
		}

		String codebasePath = args[0];
		Path exportTargetPath = Paths.get(args[1]);
		List<String> excludedPackages = Arrays.asList(args[2].split(","));

		var codebaseAnalyzer = new CodebaseAnalyzer(exportTargetPath, excludedPackages);

		codebaseAnalyzer.initializeJavaParser();
		codebaseAnalyzer.analyzeCodebase(codebasePath);

		// text render graph
		codebaseAnalyzer.exportGraphToDOT();

		// Visualize the coupling
		// visualizeCoupling();
		codebaseAnalyzer.exportGraphToSVG();
	}

	private static void renderEdge(StringBuilder svgContent, Point2D sourcePosition, Point2D targetPosition, String sourceVertex, String targetVertex) {
		svgContent.append(String.format("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" marker-end=\"url(#triangle)\" stroke=\"black\" opacity=\"0.25\" data-target=\"%s\">\n",
				(int) sourcePosition.getX(), (int) sourcePosition.getY(), (int) targetPosition.getX(), (int) targetPosition.getY(), targetVertex));
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
		Path outputPath = exportTargetPath.resolve("graph_circular_layout_" + timestamp + ".svg");

		int centerX = 500;
		int centerY = 500;
		int radius = 400;
		double angleStep = 2 * Math.PI / graph.vertexSet().size();

		StringBuilder svgContent = new StringBuilder();
		svgContent.append("<svg width=\"1000\" height=\"1000\" xmlns=\"http://www.w3.org/2000/svg\">\n");
		svgContent.append("<defs>\n");
		svgContent.append("<marker id=\"triangle\" viewBox=\"0 0 10 10\" refX=\"0\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">\n");
		svgContent.append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"black\" />\n");
		svgContent.append("</marker>\n");
		svgContent.append("</defs>\n");
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
		svgContent.append("    } else {\n");
		svgContent.append("      edges[i].setAttribute('stroke', 'black');\n");
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

		for (DefaultEdge edge : graph.edgeSet()) {
			String sourceVertex = graph.getEdgeSource(edge);
			String targetVertex = graph.getEdgeTarget(edge);
			Point2D sourcePosition = positions.get(sourceVertex);
			Point2D targetPosition = positions.get(targetVertex);

			renderEdge(svgContent, sourcePosition, targetPosition, sourceVertex, targetVertex);
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

					// Add an edge from the current package to the imported package
					graph.addEdge(packageName, importedPackage);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void visualizeCoupling() {
		// Use JGraphT's circle layout to arrange nodes in a circular layout
		CircularLayoutAlgorithm2D<String, DefaultEdge> layoutAlgorithm = new CircularLayoutAlgorithm2D<>();
		Box2D box = new Box2D(0, 0, 10, 10); // Example dimensions
		LayoutModel2D<String> layoutModel = new MapLayoutModel2D<>(box);
		layoutAlgorithm.layout(graph, layoutModel);

		// Create a JGraphX adapter for visualization
		JGraphXAdapter<String, DefaultEdge> graphAdapter = new JGraphXAdapter<>(graph);
		mxCircleLayout layout = new mxCircleLayout(graphAdapter);
		layout.execute(graphAdapter.getDefaultParent());

		// Create a JFrame to display the graph
		JFrame frame = new JFrame("Package Dependency Graph");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		// Adjusting frame size to be smaller than the screen size
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		int width = (int) (screenSize.width * 0.75); // 75% of screen width
		int height = (int) (screenSize.height * 0.75); // 75% of screen height
		frame.setSize(width, height);

		// Ensure the mxGraphComponent fits within the frame
		mxGraphComponent graphComponent = new mxGraphComponent(graphAdapter);
		// Zoom out content
		double scale = 1; // Zoom out to 75% of the original size
		graphComponent.zoomTo(scale, true);
		frame.getContentPane().add(graphComponent);

		// Optionally, center the frame on the screen
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);

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
