package com.github.thwak.confix.pool;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays ;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import com.github.thwak.confix.tree.Node;
import com.github.thwak.confix.util.IOUtils;

import script.ScriptGenerator;
import script.model.EditOp;
import script.model.EditScript;
import tree.Tree;
import tree.TreeBuilder;

public class ChangePoolGenerator {
	public ChangePool pool;
	public List<Integer> changeList = new ArrayList<Integer>();
	public List<String> postfixList = new ArrayList<String>(Arrays.asList("++", "--"));
	public List<String> infixList = new ArrayList<String>(Arrays.asList("==", "!=", "<", "<=", ">", ">=", "&&", "||", "+", "-", "*", "%", "/", "+=", "-=")) ;
	public List<String> prefixList = new ArrayList<String>(Arrays.asList("!", "++", "--"));
	public List<String> fixList;

	public ChangePoolGenerator() {
		pool = new ChangePool();
	}

	public void collect(Script script) {
		// System.out.println(script.toString()) ;
		Integer newChangeHash;
		Change revChange;

		for (Change c : script.changes.keySet()) {
			ContextIdentifier identifier = pool.getIdentifier();
			List<EditOp> ops = script.changes.get(c);
			for (EditOp op : ops) {
				Context context = identifier.getContext(op);
				updateMethod(c);

				revChange = null;

				String nodeType = c.node.label.split("::")[0];
				String locationType = c.location.label.split("::")[0];

				fixList = null;

				switch (c.type) {
					case Change.INSERT:
						revChange = new Change(c.id, Change.DELETE, c.node, c.location);
						if(nodeType.equals("InfixExpression"))
							fixList = infixList;
						else if(nodeType.equals("PostfixExpression"))
							fixList = postfixList ;
						else if(nodeType.equals("PrefixExpression"))
							fixList = prefixList;
						
						if(fixList == null)
							break;

						for(String newInfix : fixList){
							Change cloneChange = new Change(c.id, Change.DELETE, c.node, c.location) ;
							cloneChange.node.label = locationType+ "::"+newInfix;
							cloneChange.node.value = newInfix ;

							newChangeHash = new Integer((cloneChange.type+cloneChange.node.label+cloneChange.location.label).toString().hashCode());
							if (changeList.contains(newChangeHash))
								continue;

							pool.add(context, cloneChange);
							changeList.add(newChangeHash);

							System.out.println("Added Change type: " + cloneChange.type);
							System.out.println("Added Change node: " + cloneChange.node.label);
							System.out.println("Added Change location: " + cloneChange.location.label);
							System.out.println("Added Change Context: " + context.toString()+"\n");
						}
						break;
					case Change.DELETE:
						revChange = new Change(c.id, Change.INSERT, c.node, c.location);
						if(nodeType.equals("InfixExpression"))
							fixList = infixList;
						else if(nodeType.equals("PostExpression"))
							fixList = postfixList ;
						else if(nodeType.equals("PrefixExpression"))
							fixList = prefixList;

						if(fixList == null)
							break;
						
						for(String newInfix : fixList){
							Change cloneChange = new Change(c.id, Change.INSERT, c.node, c.location) ;
							cloneChange.node.label = locationType+ "::"+newInfix;
							cloneChange.node.value = newInfix ;

							newChangeHash = new Integer((cloneChange.type+cloneChange.node.label+cloneChange.location.label).toString().hashCode());
							if (changeList.contains(newChangeHash))
								continue;

							pool.add(context, cloneChange);
							changeList.add(newChangeHash);

							System.out.println("Added Change type: " + cloneChange.type);
							System.out.println("Added Change node: " + cloneChange.node.label);
							System.out.println("Added Change location: " + cloneChange.location.label);
							System.out.println("Added Change Context: " + context.toString()+"\n");
						}
						break;

					case Change.UPDATE:
					
						if(locationType.equals("InfixExpression"))
							fixList = infixList;
						else if(locationType.equals("PostExpression"))
							fixList = postfixList ;
						else if(locationType.equals("PrefixExpression"))
							fixList = prefixList;
						
						if(fixList == null)
							break;
							
						for(String newInfix : fixList){
							Change cloneChange = new Change(c.id, Change.UPDATE, c.node, c.location) ;
							cloneChange.location.label = locationType+ "::"+newInfix;
							cloneChange.location.value = newInfix ;

							newChangeHash = new Integer((cloneChange.type+cloneChange.node.label+cloneChange.location.label).toString().hashCode());
							if (changeList.contains(newChangeHash))
								continue;

							pool.add(context, cloneChange);
							changeList.add(newChangeHash);

							System.out.println("Added Change type: " + cloneChange.type);
							System.out.println("Added Change node: " + cloneChange.node.label);
							System.out.println("Added Change location: " + cloneChange.location.label);
							System.out.println("Added Change Context: " + context.toString()+"\n");

							System.out.println("This is change to string\n" + cloneChange + "\n");

						}
						

						break;
					case Change.REPLACE:
					if(locationType.equals("InfixExpression"))
							fixList = infixList;
						else if(locationType.equals("PostExpression"))
							fixList = postfixList ;
						else if(locationType.equals("PrefixExpression"))
							fixList = prefixList;

						if(fixList == null)
							break;
						
						for(String newInfix : fixList){
							Change cloneChange = new Change(c.id, Change.UPDATE, c.node, c.location) ;
							cloneChange.location.label = locationType+ "::"+newInfix;
							cloneChange.location.value = newInfix ;

							

							newChangeHash = new Integer((cloneChange.type+cloneChange.node.label+cloneChange.location.label).toString().hashCode());
							if (changeList.contains(newChangeHash))
								continue;

							pool.add(context, cloneChange);
							changeList.add(newChangeHash);

							System.out.println("Added Change type: " + cloneChange.type);
							System.out.println("Added Change node: " + cloneChange.node.label);
							System.out.println("Added Change location: " + cloneChange.location.label);
							System.out.println("Added Change Context: " + context.toString()+"\n");

							System.out.println("This is change to string\n" + cloneChange + "\n");
						}
						
						break;

				}


	

				if(revChange != null){
					newChangeHash = new Integer((revChange.type+revChange.node.label+revChange.location.label).toString().hashCode());
					if (changeList.contains(newChangeHash))
						continue;

					pool.add(context, revChange);
					changeList.add(newChangeHash);


					System.out.println("Added Change type: " + revChange.type);
					System.out.println("Added Change node: " + revChange.node.label);
					System.out.println("Added Change location: " + revChange.location.label);
					System.out.println("Added Change Context: " + context.toString()+"\n");
				}

				


				newChangeHash = new Integer((c.type+c.node.label+c.location.label).toString().hashCode());
				if (changeList.contains(newChangeHash))
					continue;

				// pool.add(context, c);
				// changeList.add(newChangeHash);

				pool.add(context, c);
				System.out.println("Added Change type: " + c.type);
				System.out.println("Added Change node: " + c.node.label);
				System.out.println("Added Change location: " + c.location.label);
				System.out.println("Added Change Context: " + context.toString()+"\n");
			}
			
		}
	}

	private void updateMethod(Change c) {
		Node n = c.node;
		while (n.parent != null && n.parent.type != ASTNode.METHOD_DECLARATION) {
			n = n.parent;
		}
		StringBuffer sb = new StringBuffer(c.id);
		if (n.parent == null)
			sb.append(":");
		else {
			sb.append(":");
			if (n.parent.astNode != null) {
				MethodDeclaration md = (MethodDeclaration) n.parent.astNode;
				sb.append(md.getName().toString());
			}
			sb.append(":");
			sb.append(n.parent.startPos);
		}
		c.id = sb.toString();
	}

	public void collect(List<File> bugFiles, List<File> cleanFiles) {
		System.out.println("bugFiles size: "+bugFiles.size());
		System.out.println("cleanFiles size: "+cleanFiles.size());
		try {
			for (int i = 0; i < bugFiles.size(); i++) {

				if(bugFiles.get(i) == null || cleanFiles.get(i) == null)
					continue;

				System.out.println("buggy file : "+bugFiles.get(i).getName());
				System.out.println("clean file : "+cleanFiles.get(i).getName());

				// Generate EditScript from before and after.
				String oldCode = IOUtils.readFile(bugFiles.get(i));
				String newCode = IOUtils.readFile(cleanFiles.get(i));

				// System.out.println("First letter of old code: "+oldCode.charAt(0));
				// System.out.println("First letter of new code: "+newCode.charAt(0));

				Tree before = TreeBuilder.buildTreeFromFile(bugFiles.get(i));
				Tree after = TreeBuilder.buildTreeFromFile(cleanFiles.get(i));

				if(before == null || after == null)
					System.out.println("Tree is null");

				EditScript editScript = ScriptGenerator.generateScript(before, after);
				// Convert EditScript to Script.
				editScript = Converter.filter(editScript);
				EditScript combined = Converter.combineEditOps(editScript);
				Script script = Converter.convert("0", combined, oldCode, newCode);
				collect(script);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

