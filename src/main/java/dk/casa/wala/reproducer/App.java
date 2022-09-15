package dk.casa.wala.reproducer;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.demandpa.alg.DemandRefinementPointsTo;
import com.ibm.wala.demandpa.alg.refinepolicy.*;
import com.ibm.wala.demandpa.alg.statemachine.DummyStateMachine;
import com.ibm.wala.demandpa.alg.statemachine.StateMachineFactory;
import com.ibm.wala.demandpa.flowgraph.IFlowLabel;
import com.ibm.wala.demandpa.util.MemoryAccessMap;
import com.ibm.wala.demandpa.util.SimpleMemoryAccessMap;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.perf.Stopwatch;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE;

public class App {
	private static AnalysisScope scope;
	private static CHACallGraph chaCG;

	private static IClass lookupClass(String name) {
		IClass cls = chaCG.getClassHierarchy().lookupClass(TypeReference.findOrCreate(scope.getApplicationLoader(), "L" + name));
		if(cls == null)
			throw new NullPointerException();
		return cls;
	}

	private static Collection<File> getLibraries(Path repo) {
		return FileUtils.listFiles(repo.toFile(), FileFilterUtils.asFileFilter(file -> {
			String path = file.getPath();
			return path.endsWith(".jar") && (path.contains("gcache/caches/modules-2/") || path.contains("libs/"));
		}), FileFilterUtils.trueFileFilter());
	}

	private static class OwnerAndSelector {
		public final IClass owner;
		public final Selector selector;

		public OwnerAndSelector(String[] parts) {
			this.owner = lookupClass(parts[0]);
			this.selector = new Selector(Atom.findOrCreateUnicodeAtom(parts[1]), Descriptor.findOrCreateUTF8(parts[2]));
		}

		@Override
		public String toString() {
			try {
				return String.format("%s.%s%s", this.owner.getName().toUnicodeString(), this.selector.getName().toUnicodeString(), this.selector.getDescriptor().toUnicodeString());
			} catch (UTFDataFormatException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static class Query {
		public final OwnerAndSelector from, to;

		private Query(OwnerAndSelector from, OwnerAndSelector to) {
			this.from = from;
			this.to = to;
		}
	}

    public static void main( String[] args ) {
		System.out.println("Initialising WALA callgraph and memory access map");

        Path repoPath = Path.of("vert.x");

        List<String> directories = Arrays.asList("vert.x/target/classes", "vert.x/target/test-classes");

		try {
			File scopeFile = File.createTempFile("streamliner_scope", ".txt");
			scopeFile.deleteOnExit();

			try(PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(scopeFile)))) {
				// Java standard library
				writer.println("Primordial,Java,stdlib,none");
				writer.println("Primordial,Java,jarFile,primordial.jar.model");

				// Dependencies
				for(File jarFile : getLibraries(repoPath))
					writer.format("Application,Java,jarFile,%s\n", jarFile.getPath());

				// Class file directories
				for(String classDirectory : directories)
					writer.format("Application,Java,binaryDir,%s\n", classDirectory);
			}

			scope = AnalysisScopeReader.instance.readJavaScope(scopeFile.getPath(), null, App.class.getClassLoader());

			// We need a baseline call graph.  Here we use a CHACallGraph based on a ClassHierarchy.
			ClassHierarchy cha = ClassHierarchyFactory.make(scope);
			chaCG = new CHACallGraph(cha);

			// Read entrypoints from file
			File entriesFile = new File("entries/" + repoPath.getFileName());
			Collection<Entrypoint> entrypoints;
			try(BufferedReader reader = new BufferedReader(new FileReader(entriesFile))) {
				entrypoints = reader.lines()
					.map(line -> {
						String[] split = line.split(",");
						IClass cls = lookupClass(split[0]);
						return cls.getMethod(
							new Selector(
								Atom.findOrCreateUnicodeAtom(split[1]),
								Descriptor.findOrCreateUTF8(split[2])
							)
						);
					}).filter(Objects::nonNull).map(mth -> new DefaultEntrypoint(mth, cha))
					.collect(Collectors.toList());
			} catch(IOException e) {
				throw new RuntimeException(e);
			}

			WUtil.time("CHA CG Init", () -> {
				try {
					chaCG.init(entrypoints);
				} catch (CancelException e) {
					throw new RuntimeException(e);
				}
			});

			AnalysisOptions options = new AnalysisOptions();
			options.setEntrypoints(entrypoints);
			IAnalysisCacheView cache = new AnalysisCacheImpl();

			/*
			SSAPropagationCallGraphBuilder builder = Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);
			CallGraph cg = WUtil.time("0 CFA CG Init", () -> {
				try {
					return builder.makeCallGraph(options);
				} catch (CancelException e) {
					throw new RuntimeException(e);
				}
			});
			 */

			// We also need a heap model to create InstanceKeys for allocation sites, etc.
			// Here we use a 0-1 CFA builder, which will give a heap abstraction similar to
			// context-insensitive Andersen's analysis
			HeapModel heapModel = Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha);
			// The MemoryAccessMap helps the demand analysis find matching field reads and writes
			MemoryAccessMap mam = new SimpleMemoryAccessMap(chaCG, heapModel, false);
			// The StateMachineFactory helps in tracking additional states like calling contexts.
			// For context-insensitive analysis we use a DummyStateMachine.Factory
			StateMachineFactory<IFlowLabel> stateMachineFactory = new DummyStateMachine.Factory<>();
			DemandRefinementPointsTo drpt = DemandRefinementPointsTo.makeWithDefaultFlowGraph(
					chaCG, heapModel, mam, cha, options, stateMachineFactory);
			// The RefinementPolicyFactory determines how the analysis refines match edges (see PLDI'06
			// paper).  Here we use a policy that does not perform refinement and just uses a fixed budget
			// for a single pass
			RefinementPolicyFactory refinementPolicyFactory =
					new SinglePassRefinementPolicy.Factory(
							new OnlyArraysPolicy(),
							//new NeverRefineFieldsPolicy(),
							new NeverRefineCGPolicy(), 100000);
			drpt.setRefinementPolicyFactory(refinementPolicyFactory);

			System.out.println("Successfully initialised WALA");

			File queriesFile = new File("queries/" + repoPath.getFileName());
			Collection<Query> queries;
			try(BufferedReader reader = new BufferedReader(new FileReader(queriesFile))) {
				queries = reader.lines()
						.distinct()
						.map(line -> {
							String[] split = line.split(",");
							OwnerAndSelector from = new OwnerAndSelector(split);
							System.arraycopy(split, 3, split, 0, 3);
							return new Query(from, new OwnerAndSelector(split));
						}) .collect(Collectors.toList());
			} catch(IOException e) {
				throw new RuntimeException(e);
			}

			System.out.format("Loaded %d queries\n", queries.size());

			for(Query query : queries) {
				System.out.format("\nStarting WALA query in %s for receiver of call to %s\n",
						query.from,
						query.to);


				IClass cls = query.from.owner;
				IMethod method = cls.getMethod(query.from.selector);

				CGNode node = chaCG.getNode(method, EVERYWHERE);
				if(node == null) { // ???
					System.out.println("CGNodeNull?");
					continue;
				}

				// This piece of code finds calls in the method that match the given name and descriptor
				IR ir = node.getIR();
				List<SSAAbstractInvokeInstruction> matchingCalls = new ArrayList<>();
				ir.iterateCallSites().forEachRemaining(csr -> {
					SSAAbstractInvokeInstruction invoke = ir.getCalls(csr)[0];
					MethodReference target = invoke.getDeclaredTarget();
					if(target.getDeclaringClass().getName().equals(query.to.owner.getName())
							&& target.getSelector().equals(query.to.selector))
						matchingCalls.add(invoke);
				});

				if(matchingCalls.size() == 0)
					throw new NoSuchElementException();

				// Now we have the matching call site, so we can query for the type of the receiver of the call.
				for(SSAAbstractInvokeInstruction invoke : matchingCalls) {
					PointerKey pk = heapModel.getPointerKeyForLocal(node, invoke.getUse(0));
					Pair<DemandRefinementPointsTo.PointsToResult, Collection<InstanceKey>> res;
					Stopwatch stopwatch = new Stopwatch();
					try {
						stopwatch.start();
						res = drpt.getPointsTo(pk, k -> true);
						stopwatch.stop();
					} catch (AssertionError | IllegalArgumentException | NullPointerException exc) {
						System.out.println(exc);
						continue;
					}

					System.out.format("Points to query finished with %s in %.2fs\n", res.fst, (float) stopwatch.getElapsedMillis() / 1000.f);

					if (res.fst != DemandRefinementPointsTo.PointsToResult.SUCCESS)
						continue;

					// We transform the returned list of allocation sites into a list of concrete types
					Set<String> concreteTypes = res.snd.stream().map(InstanceKey::getConcreteType)
							.map(IClass::getName).map(type -> type.getPackage() + "/" + type.getClassName())
							.collect(Collectors.toSet());

					if (concreteTypes.size() == 0) { // ???
						System.out.println("WALA query found 0 concrete types?");
						continue;
					} else if (concreteTypes.size() > 1) {
						System.out.format("WALA query found %d possible concrete receiver types: %s\n", concreteTypes.size(),
								concreteTypes.stream().limit(10).collect(Collectors.toList()));
						continue;
					}

					// Full success: only one possible concrete type!
					System.out.format("Query success: %s\n", concreteTypes.iterator().next());
				}
			}
		} catch(ClassHierarchyException | IOException | UnimplementedError | NullPointerException exc) {
			System.err.println("Failed to initialise WALA oracle!");
			exc.printStackTrace();
		}
	}
}
