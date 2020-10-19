/*
 * Copyright (c) 2020 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.accesswidener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

public final class AccessWidener {
	String namespace;
	final Map<String, Access> classAccess = new HashMap<>();
	final Map<EntryTriple, Access> methodAccess = new HashMap<>();
	final Map<EntryTriple, Access> fieldAccess = new HashMap<>();
	private final Set<String> classes = new LinkedHashSet<>();

	public void read(BufferedReader reader, String currentNamespace) throws IOException {
		String[] header = reader.readLine().split("\\s+");

		if (header.length != 3 || !header[0].equals("accessWidener")) {
			throw new UnsupportedOperationException("Invalid access access widener file");
		}

		if (!header[1].equals("v1")) {
			throw new RuntimeException(String.format("Unsupported access widener format (%s)", header[1]));
		}

		if (currentNamespace != null && !header[2].equals(currentNamespace)) {
			throw new RuntimeException(String.format("Namespace (%s) does not match current runtime namespace (%s)", header[2], currentNamespace));
		}

		if (namespace != null) {
			if (!namespace.equals(header[2])) {
				throw new RuntimeException(String.format("Namespace mismatch, expected %s got %s", namespace, header[2]));
			}
		}

		namespace = header[2];

		String line;
		Set<String> targets = new LinkedHashSet<>();

		while ((line = reader.readLine()) != null) {
			//Comment handling
			int commentPos = line.indexOf('#');

			if (commentPos >= 0) {
				line = line.substring(0, commentPos).trim();
			}

			if (line.isEmpty()) continue;

			String[] split = line.split("\\s+");

			if (split.length != 3 && split.length != 5) {
				throw new RuntimeException(String.format("Invalid line (%s)", line));
			}

			String access = split[0];

			targets.add(split[2].replaceAll("/", "."));

			switch (split[1]) {
			case "class":
				if (split.length != 3) {
					throw new RuntimeException(String.format("Expected (<access>\tclass\t<className>) got (%s)", line));
				}

				classAccess.put(split[2], applyAccess(access, classAccess.getOrDefault(split[2], ClassAccess.DEFAULT), null));
				break;
			case "field":
				if (split.length != 5) {
					throw new RuntimeException(String.format("Expected (<access>\tfield\t<className>\t<fieldName>\t<fieldDesc>) got (%s)", line));
				}

				addOrMerge(fieldAccess, new EntryTriple(split[2], split[3], split[4]), access, FieldAccess.DEFAULT);
				break;
			case "method":
				if (split.length != 5) {
					throw new RuntimeException(String.format("Expected (<access>\tmethod\t<className>\t<methodName>\t<methodDesc>) got (%s)", line));
				}

				addOrMerge(methodAccess, new EntryTriple(split[2], split[3], split[4]), access, MethodAccess.DEFAULT);
				break;
			default:
				throw new UnsupportedOperationException("Unsupported type " + split[1]);
			}
		}

		Set<String> parentClasses = new LinkedHashSet<>();

		//Also transform all parent classes
		for (String clazz : targets) {
			while (clazz.contains("$")) {
				clazz = clazz.substring(0, clazz.lastIndexOf("$"));
				parentClasses.add(clazz);
			}
		}

		classes.addAll(targets);
		classes.addAll(parentClasses);
	}

	public void write(StringWriter writer) {
		writer.write("accessWidener\tv1\t");
		writer.write(namespace);
		writer.write("\n");

		for (Map.Entry<String, Access> entry : classAccess.entrySet()) {
			for (String s : getAccesses(entry.getValue())) {
				writer.write(s);
				writer.write("\tclass\t");
				writer.write(entry.getKey());
				writer.write("\n");
			}
		}

		for (Map.Entry<EntryTriple, Access> entry : methodAccess.entrySet()) {
			writeEntry(writer, "method", entry.getKey(), entry.getValue());
		}

		for (Map.Entry<EntryTriple, Access> entry : fieldAccess.entrySet()) {
			writeEntry(writer, "field", entry.getKey(), entry.getValue());
		}
	}

	private void writeEntry(StringWriter writer, String type, EntryTriple entryTriple, Access access) {
		for (String s : getAccesses(access)) {
			writer.write(s);
			writer.write("\t");
			writer.write(type);
			writer.write("\t");
			writer.write(entryTriple.getOwner());
			writer.write("\t");
			writer.write(entryTriple.getName());
			writer.write("\t");
			writer.write(entryTriple.getDesc());
			writer.write("\n");
		}
	}

	private List<String> getAccesses(Access access) {
		List<String> accesses = new ArrayList<>();

		if (access == ClassAccess.ACCESSIBLE || access == MethodAccess.ACCESSIBLE || access == FieldAccess.ACCESSIBLE || access == MethodAccess.ACCESSIBLE_EXTENDABLE || access == ClassAccess.ACCESSIBLE_EXTENDABLE || access == FieldAccess.ACCESSIBLE_MUTABLE) {
			accesses.add("accessible");
		}

		if (access == ClassAccess.EXTENDABLE || access == MethodAccess.EXTENDABLE || access == MethodAccess.ACCESSIBLE_EXTENDABLE || access == ClassAccess.ACCESSIBLE_EXTENDABLE) {
			accesses.add("extendable");
		}

		if (access == FieldAccess.MUTABLE || access == FieldAccess.ACCESSIBLE_MUTABLE) {
			accesses.add("mutable");
		}

		return accesses;
	}

	void addOrMerge(Map<EntryTriple, Access> map, EntryTriple entry, Access access) {
		if (entry == null || access == null) {
			throw new RuntimeException("Input entry or access is null");
		}

		Access merged = null;

		if (access instanceof ClassAccess) {
			merged = ClassAccess.DEFAULT;
		} else if (access instanceof MethodAccess) {
			merged = MethodAccess.DEFAULT;
		} else if (access instanceof FieldAccess) {
			merged = FieldAccess.DEFAULT;
		}

		merged = mergeAccess(merged, access);

		map.put(entry, merged);
	}

	private static Access mergeAccess(Access a, Access b) {
		Access access = a;

		if (b == ClassAccess.ACCESSIBLE || b == MethodAccess.ACCESSIBLE || b == FieldAccess.ACCESSIBLE || b == MethodAccess.ACCESSIBLE_EXTENDABLE || b == ClassAccess.ACCESSIBLE_EXTENDABLE || b == FieldAccess.ACCESSIBLE_MUTABLE) {
			access = access.makeAccessible();
		}

		if (b == ClassAccess.EXTENDABLE || b == MethodAccess.EXTENDABLE || b == MethodAccess.ACCESSIBLE_EXTENDABLE || b == ClassAccess.ACCESSIBLE_EXTENDABLE) {
			access = access.makeExtendable();
		}

		if (b == FieldAccess.MUTABLE || b == FieldAccess.ACCESSIBLE_MUTABLE) {
			access = access.makeMutable();
		}

		return access;
	}

	void addOrMerge(Map<EntryTriple, Access> map, EntryTriple entry, String access, Access defaultAccess) {
		if (entry == null || access == null) {
			throw new RuntimeException("Input entry or access is null");
		}

		map.put(entry, applyAccess(access, map.getOrDefault(entry, defaultAccess), entry));
	}

	private Access applyAccess(String input, Access access, EntryTriple entryTriple) {
		switch (input.toLowerCase(Locale.ROOT)) {
		case "accessible":
			makeClassAccessible(entryTriple);
			return access.makeAccessible();
		case "extendable":
			makeClassExtendable(entryTriple);
			return access.makeExtendable();
		case "mutable":
			return access.makeMutable();
		default:
			throw new UnsupportedOperationException("Unknown access type:" + input);
		}
	}

	private void makeClassAccessible(EntryTriple entryTriple) {
		if (entryTriple == null) return;
		classAccess.put(entryTriple.getOwner(), applyAccess("accessible", classAccess.getOrDefault(entryTriple.getOwner(), ClassAccess.DEFAULT), null));
	}

	private void makeClassExtendable(EntryTriple entryTriple) {
		if (entryTriple == null) return;
		classAccess.put(entryTriple.getOwner(), applyAccess("extendable", classAccess.getOrDefault(entryTriple.getOwner(), ClassAccess.DEFAULT), null));
	}

	Access getClassAccess(String className) {
		return classAccess.getOrDefault(className, ClassAccess.DEFAULT);
	}

	Access getFieldAccess(EntryTriple entryTriple) {
		return fieldAccess.getOrDefault(entryTriple, FieldAccess.DEFAULT);
	}

	Access getMethodAccess(EntryTriple entryTriple) {
		return methodAccess.getOrDefault(entryTriple, MethodAccess.DEFAULT);
	}

	public Set<String> getTargets() {
		return classes;
	}

	public ClassVisitor createClassVisitor(int api, ClassVisitor visitor, AccessWidener accessWidener) {
		return new AccessWidenerVisitor(api, visitor, accessWidener);
	}

	private static int makePublic(int i) {
		return (i & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
	}

	private static int makeProtected(int i) {
		if ((i & Opcodes.ACC_PUBLIC) != 0) {
			//Return i if public
			return i;
		}

		return (i & ~(Opcodes.ACC_PRIVATE)) | Opcodes.ACC_PROTECTED;
	}

	private static int makeFinalIfPrivate(int access, String name, int ownerAccess) {
		// Dont make constructors final
		if (name.equals("<init>")) {
			return access;
		}

		// Skip interface and static methods
		if ((ownerAccess & Opcodes.ACC_INTERFACE) != 0 || (access & Opcodes.ACC_STATIC) != 0) {
			return access;
		}

		if ((access & Opcodes.ACC_PRIVATE) != 0) {
			return access | Opcodes.ACC_FINAL;
		}

		return access;
	}

	private static int removeFinal(int i) {
		return i & ~Opcodes.ACC_FINAL;
	}

	interface Access extends AccessOperator {
		Access makeAccessible();

		Access makeExtendable();

		Access makeMutable();
	}

	enum ClassAccess implements Access {
		DEFAULT((access, name, ownerAccess) -> access),
		ACCESSIBLE((access, name, ownerAccess) -> makePublic(access)),
		EXTENDABLE((access, name, ownerAccess) -> makePublic(removeFinal(access))),
		ACCESSIBLE_EXTENDABLE((access, name, ownerAccess) -> makePublic(removeFinal(access)));

		private final AccessOperator operator;

		ClassAccess(AccessOperator operator) {
			this.operator = operator;
		}

		@Override
		public Access makeAccessible() {
			if (this == EXTENDABLE || this == ACCESSIBLE_EXTENDABLE) {
				return ACCESSIBLE_EXTENDABLE;
			}

			return ACCESSIBLE;
		}

		@Override
		public Access makeExtendable() {
			if (this == ACCESSIBLE || this == ACCESSIBLE_EXTENDABLE) {
				return ACCESSIBLE_EXTENDABLE;
			}

			return EXTENDABLE;
		}

		@Override
		public Access makeMutable() {
			throw new UnsupportedOperationException("Classes cannot be made mutable");
		}

		@Override
		public int apply(int access, String targetName, int ownerAccess) {
			return operator.apply(access, targetName, ownerAccess);
		}
	}

	enum MethodAccess implements Access {
		DEFAULT((access, name, ownerAccess) -> access),
		ACCESSIBLE((access, name, ownerAccess) -> makePublic(makeFinalIfPrivate(access, name, ownerAccess))),
		EXTENDABLE((access, name, ownerAccess) -> makeProtected(removeFinal(access))),
		ACCESSIBLE_EXTENDABLE((access, name, owner) -> makePublic(removeFinal(access)));

		private final AccessOperator operator;

		MethodAccess(AccessOperator operator) {
			this.operator = operator;
		}

		@Override
		public Access makeAccessible() {
			if (this == EXTENDABLE || this == ACCESSIBLE_EXTENDABLE) {
				return ACCESSIBLE_EXTENDABLE;
			}

			return ACCESSIBLE;
		}

		@Override
		public Access makeExtendable() {
			if (this == ACCESSIBLE || this == ACCESSIBLE_EXTENDABLE) {
				return ACCESSIBLE_EXTENDABLE;
			}

			return EXTENDABLE;
		}

		@Override
		public Access makeMutable() {
			throw new UnsupportedOperationException("Methods cannot be made mutable");
		}

		@Override
		public int apply(int access, String targetName, int ownerAccess) {
			return operator.apply(access, targetName, ownerAccess);
		}
	}

	enum FieldAccess implements Access {
		DEFAULT((access, name, ownerAccess) -> access),
		ACCESSIBLE((access, name, ownerAccess) -> makePublic(access)),
		MUTABLE((access, name, ownerAccess) -> removeFinal(access)),
		ACCESSIBLE_MUTABLE((access, name, ownerAccess) -> makePublic(removeFinal(access)));

		private final AccessOperator operator;

		FieldAccess(AccessOperator operator) {
			this.operator = operator;
		}

		@Override
		public Access makeAccessible() {
			if (this == MUTABLE || this == ACCESSIBLE_MUTABLE) {
				return ACCESSIBLE_MUTABLE;
			}

			return ACCESSIBLE;
		}

		@Override
		public Access makeExtendable() {
			throw new UnsupportedOperationException("Fields cannot be made extendable");
		}

		@Override
		public Access makeMutable() {
			if (this == ACCESSIBLE || this == ACCESSIBLE_MUTABLE) {
				return ACCESSIBLE_MUTABLE;
			}

			return MUTABLE;
		}

		@Override
		public int apply(int access, String targetName, int ownerAccess) {
			return operator.apply(access, targetName, ownerAccess);
		}
	}

	@FunctionalInterface
	interface AccessOperator {
		int apply(int access, String targetName, int ownerAccess);
	}
}
