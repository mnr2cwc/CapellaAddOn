package com.mnr.capella.validationrules.views;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.ui.part.ViewPart;

/**
 * The custom validation UI. Validation logic will be added independently of
 * Capella's built-in validation framework.
 */
public class ValidationRulesView extends ViewPart {

	public static final String ID = "com.mnr.capella.validationrules.views.ValidationRulesView";

	private Table resultsTable;
	private Label statusLabel;
	private Combo modelPicker;
	private final List<IFile> modelFiles = new ArrayList<>();

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		Label heading = new Label(parent, SWT.NONE);
		heading.setText("Custom model validation");

		Composite modelSelector = new Composite(parent, SWT.NONE);
		modelSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		modelSelector.setLayout(new GridLayout(3, false));
		Label modelLabel = new Label(modelSelector, SWT.NONE);
		modelLabel.setText("Capella model:");
		modelPicker = new Combo(modelSelector, SWT.DROP_DOWN | SWT.READ_ONLY);
		modelPicker.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button refreshButton = new Button(modelSelector, SWT.PUSH);
		refreshButton.setText("Refresh Models");
		refreshButton.addListener(SWT.Selection, event -> refreshModelPicker());

		Button runButton = new Button(parent, SWT.PUSH);
		runButton.setText("Run Validation");
		runButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
		runButton.addListener(SWT.Selection, event -> runValidation());

		resultsTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
		resultsTable.setHeaderVisible(true);
		resultsTable.setLinesVisible(true);
		resultsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		createColumn("Severity", 100);
		createColumn("Rule", 220);
		createColumn("Model element", 280);
		createColumn("Message", 460);

		statusLabel = new Label(parent, SWT.WRAP);
		statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		refreshModelPicker();
	}

	private void createColumn(String title, int width) {
		TableColumn column = new TableColumn(resultsTable, SWT.NONE);
		column.setText(title);
		column.setWidth(width);
	}

	private void runValidation() {
		resultsTable.removeAll();
		int selectedIndex = modelPicker.getSelectionIndex();
		if (selectedIndex < 0) {
			statusLabel.setText("Select a Capella model from the dropdown first.");
			return;
		}

		IFile modelFile = modelFiles.get(selectedIndex);
		URI modelUri = URI.createPlatformResourceURI(modelFile.getFullPath().toString(), true);
		Session session = SessionManager.INSTANCE.getSession(modelUri, new NullProgressMonitor());
		if (session == null || !session.isOpen()) {
			statusLabel.setText("Unable to open the selected Capella model.");
			return;
		}

		int checkedFunctions = 0;
		for (Resource semanticResource : session.getSemanticResources()) {
			for (EObject root : semanticResource.getContents()) {
				checkedFunctions += validateFunctionSummary(root);
			}
		}
		int failures = resultsTable.getItemCount();
		statusLabel.setText(String.format("Checked %d Capella functions: %d missing a summary.", checkedFunctions, failures));
	}

	private void refreshModelPicker() {
		modelFiles.clear();
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept(new IResourceVisitor() {
				@Override
				public boolean visit(IResource resource) throws CoreException {
					if (resource.getType() == IResource.FILE && "aird".equalsIgnoreCase(resource.getFileExtension())) {
						modelFiles.add((IFile) resource);
					}
					return true;
				}
			});
		} catch (CoreException exception) {
			statusLabel.setText("Could not read models from the workspace: " + exception.getMessage());
			return;
		}

		Collections.sort(modelFiles, Comparator.comparing(file -> file.getFullPath().toString()));
		modelPicker.removeAll();
		for (IFile modelFile : modelFiles) {
			modelPicker.add(modelFile.getFullPath().toString());
		}
		if (!modelFiles.isEmpty()) {
			modelPicker.select(0);
			statusLabel.setText("Select a model and click Run Validation.");
		} else {
			statusLabel.setText("No Capella models found. Create or import a Capella model into this workspace.");
		}
	}

	private int validateFunctionSummary(EObject modelRoot) {
		int checkedFunctions = 0;
		if (modelRoot instanceof AbstractFunction) {
			checkedFunctions += validateFunctionSummary((AbstractFunction) modelRoot);
		}
		for (java.util.Iterator<EObject> contents = modelRoot.eAllContents(); contents.hasNext();) {
			EObject element = contents.next();
			if (element instanceof AbstractFunction) {
				checkedFunctions += validateFunctionSummary((AbstractFunction) element);
			}
		}
		return checkedFunctions;
	}

	private int validateFunctionSummary(AbstractFunction function) {
		boolean isLeafFunction = function.getOwnedFunctions().isEmpty();
		boolean isRootFunction = !(function.eContainer() instanceof AbstractFunction);
		if (isRootFunction || !isLeafFunction) {
			return 0;
		}

		String summary = function.getSummary();
		if (summary == null || summary.trim().isEmpty()) {
			String name = function.getName();
			addResult("Error", "Function summary required", name == null || name.isBlank() ? "<unnamed function>" : name,
					"This Capella function has no summary.");
		}
		return 1;
	}

	/** Adds one result row for use by the future validation service. */
	public void addResult(String severity, String rule, String modelElement, String message) {
		TableItem item = new TableItem(resultsTable, SWT.NONE);
		item.setText(new String[] { severity, rule, modelElement, message });
	}

	@Override
	public void setFocus() {
		resultsTable.setFocus();
	}
}
