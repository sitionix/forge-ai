function list(value) {
  return Array.isArray(value) ? value : [];
}

function asText(value, fallback = '') {
  if (value === null || value === undefined) {
    return fallback;
  }
  return String(value);
}

function addUnique(items, value) {
  if (value && !items.includes(value)) {
    items.push(value);
  }
}

function indexBy(items, key) {
  const result = new Map();
  for (const item of list(items)) {
    const value = item?.[key];
    if (typeof value === 'string' && value) {
      result.set(value, item);
    }
  }
  return result;
}

function groupBy(items, key) {
  const result = new Map();
  for (const item of list(items)) {
    const value = item?.[key];
    if (typeof value !== 'string' || !value) {
      continue;
    }
    if (!result.has(value)) {
      result.set(value, []);
    }
    result.get(value).push(item);
  }
  return result;
}

export function buildJarvisFlowViewModels(response = {}) {
  const explanationsByIndex = new Map();
  for (const explanation of list(response.flowExplanations)) {
    if (Number.isInteger(explanation?.flowIndex)) {
      explanationsByIndex.set(explanation.flowIndex, explanation);
    }
  }
  return list(response.flows)
    .slice()
    .sort((left, right) => Number(left?.flowIndex || 0) - Number(right?.flowIndex || 0))
    .map((flow) => buildFlowViewModel(flow, explanationsByIndex.get(flow?.flowIndex)));
}

export function buildFlowViewModel(flow = {}, explanation = null) {
  const nodes = list(flow.nodes);
  const transitions = list(flow.transitions);
  const boundaries = list(flow.boundaries);
  const evidence = list(flow.evidence);
  const nodeByRef = indexBy(nodes, 'nodeRef');
  const transitionByRef = indexBy(transitions, 'transitionRef');
  const boundaryByRef = indexBy(boundaries, 'boundaryRef');
  const evidenceByRef = indexBy(evidence, 'evidenceRef');
  const evidenceByOwnerRef = groupBy(evidence, 'ownerRef');
  const stepExplanationByNodeRef = indexBy(explanation?.steps || [], 'nodeRef');
  const transitionExplanationByRef = indexBy(explanation?.transitionExplanations || [], 'transitionRef');
  const boundaryExplanationByRef = indexBy(explanation?.boundaries || [], 'boundaryRef');
  const outgoingTransitionsByNodeRef = groupBy(transitions, 'fromNodeRef');
  const boundariesByNodeRef = groupBy(boundaries, 'fromNodeRef');
  const matchedAnchorRefs = new Set();
  const debugWarnings = [];

  for (const anchor of list(flow.matchedAnchors)) {
    const ref = anchor?.nodeRef || anchor?.anchorRef;
    if (typeof ref === 'string' && nodeByRef.has(ref)) {
      matchedAnchorRefs.add(ref);
    } else if (typeof ref === 'string' && ref) {
      debugWarnings.push(`Matched anchor references a missing flow node (${ref}).`);
    }
  }

  validateRefs({
    flow,
    explanation,
    nodeByRef,
    transitionByRef,
    boundaryByRef,
    evidenceByRef,
    evidenceByOwnerRef,
    debugWarnings,
  });

  const model = {
    flow,
    explanation,
    flowIndex: Number(flow.flowIndex || 0),
    source: asText(flow.source, '-'),
    entrypoint: flow.entrypoint || {},
    entrypointOrigin: asText(flow.entrypointOrigin, ''),
    complete: flow.complete !== false,
    nodes,
    transitions,
    boundaries,
    evidence,
    nodeByRef,
    transitionByRef,
    boundaryByRef,
    evidenceByRef,
    evidenceByOwnerRef,
    stepExplanationByNodeRef,
    transitionExplanationByRef,
    boundaryExplanationByRef,
    outgoingTransitionsByNodeRef,
    boundariesByNodeRef,
    matchedAnchorRefs,
    debugWarnings,
    treeRows: [],
  };
  model.treeRows = buildGraphRows(model);
  return model;
}

function validateRefs(context) {
  const {
    flow,
    explanation,
    nodeByRef,
    transitionByRef,
    boundaryByRef,
    evidenceByRef,
    evidenceByOwnerRef,
    debugWarnings,
  } = context;

  const entrypointRef = flow?.entrypoint?.nodeRef;
  if (typeof entrypointRef === 'string' && entrypointRef && !nodeByRef.has(entrypointRef)) {
    debugWarnings.push(`Entrypoint references a missing flow node (${entrypointRef}).`);
  }

  for (const transition of list(flow.transitions)) {
    if (!nodeByRef.has(transition?.fromNodeRef)) {
      debugWarnings.push(`Transition ${transition?.transitionRef || '-'} references a missing caller node.`);
    }
    if (!nodeByRef.has(transition?.toNodeRef)) {
      debugWarnings.push(`Transition ${transition?.transitionRef || '-'} references a missing target node.`);
    }
    validateEvidenceOwner(transition?.transitionRef, transition?.evidenceRefs, evidenceByRef, evidenceByOwnerRef, debugWarnings);
  }

  for (const boundary of list(flow.boundaries)) {
    if (!nodeByRef.has(boundary?.fromNodeRef)) {
      debugWarnings.push(`Boundary ${boundary?.boundaryRef || '-'} references a missing owner node.`);
    }
    validateEvidenceOwner(boundary?.boundaryRef, boundary?.evidenceRefs, evidenceByRef, evidenceByOwnerRef, debugWarnings);
  }

  for (const item of list(explanation?.steps)) {
    if (!nodeByRef.has(item?.nodeRef)) {
      debugWarnings.push(`Explanation step references a missing node (${item?.nodeRef || '-'}).`);
    }
    validateEvidenceOwner(item?.nodeRef, item?.evidenceRefs, evidenceByRef, evidenceByOwnerRef, debugWarnings);
    for (const ref of list(item?.transitionRefs)) {
      if (!transitionByRef.has(ref)) {
        debugWarnings.push(`Explanation step references a missing transition (${ref}).`);
      }
    }
  }

  for (const item of list(explanation?.transitionExplanations)) {
    if (!transitionByRef.has(item?.transitionRef)) {
      debugWarnings.push(`Transition explanation references a missing transition (${item?.transitionRef || '-'}).`);
    }
    validateEvidenceOwner(item?.transitionRef, item?.evidenceRefs, evidenceByRef, evidenceByOwnerRef, debugWarnings);
  }

  for (const item of list(explanation?.boundaries)) {
    if (!boundaryByRef.has(item?.boundaryRef)) {
      debugWarnings.push(`Boundary explanation references a missing boundary (${item?.boundaryRef || '-'}).`);
    }
    if (!nodeByRef.has(item?.fromNodeRef)) {
      debugWarnings.push(`Boundary explanation references a missing owner node (${item?.fromNodeRef || '-'}).`);
    }
    validateEvidenceOwner(item?.boundaryRef, item?.evidenceRefs, evidenceByRef, evidenceByOwnerRef, debugWarnings);
  }

  for (const item of list(explanation?.narrative)) {
    for (const ref of list(item?.nodeRefs)) {
      if (!nodeByRef.has(ref)) {
        debugWarnings.push(`Narrative references a missing node (${ref}).`);
      }
    }
    for (const ref of list(item?.transitionRefs)) {
      if (!transitionByRef.has(ref)) {
        debugWarnings.push(`Narrative references a missing transition (${ref}).`);
      }
    }
    for (const ref of list(item?.boundaryRefs)) {
      if (!boundaryByRef.has(ref)) {
        debugWarnings.push(`Narrative references a missing boundary (${ref}).`);
      }
    }
  }
}

function validateEvidenceOwner(ownerRef, refs, evidenceByRef, evidenceByOwnerRef, debugWarnings) {
  if (!ownerRef) {
    return;
  }
  const ownerEvidence = new Set(list(evidenceByOwnerRef.get(ownerRef)).map((item) => item.evidenceRef));
  for (const ref of list(refs)) {
    if (!evidenceByRef.has(ref)) {
      debugWarnings.push(`Evidence reference ${ref} is missing from this flow.`);
    } else if (!ownerEvidence.has(ref)) {
      debugWarnings.push(`Evidence reference ${ref} does not belong to its local owner.`);
    }
  }
}

function buildGraphRows(model) {
  const rootRef = model.entrypoint?.nodeRef;
  if (!rootRef || !model.nodeByRef.has(rootRef)) {
    return [{ kind: 'missing-root', depth: 0, label: model.entrypoint?.label || 'Entrypoint' }];
  }

  const rows = [];
  const renderedNodes = new Set();
  const stack = [{ kind: 'node', nodeRef: rootRef, depth: 0, ancestry: [] }];

  while (stack.length > 0) {
    const item = stack.pop();
    if (item.kind === 'node') {
      const node = model.nodeByRef.get(item.nodeRef);
      if (!node) {
        rows.push({ kind: 'missing-node', depth: item.depth, label: item.nodeRef });
        continue;
      }
      const currentAncestry = [...item.ancestry, item.nodeRef];
      rows.push({
        kind: 'node',
        depth: item.depth,
        node,
        matched: model.matchedAnchorRefs.has(item.nodeRef),
        explanation: model.stepExplanationByNodeRef.get(item.nodeRef),
        evidence: evidenceForOwner(model, item.nodeRef, model.stepExplanationByNodeRef.get(item.nodeRef)?.evidenceRefs),
      });
      renderedNodes.add(item.nodeRef);

      for (const boundary of list(model.boundariesByNodeRef.get(item.nodeRef)).slice().reverse()) {
        stack.push({
          kind: 'boundary',
          depth: item.depth + 1,
          boundary,
        });
      }
      for (const transition of list(model.outgoingTransitionsByNodeRef.get(item.nodeRef)).slice().reverse()) {
        stack.push({
          kind: 'transition',
          depth: item.depth + 1,
          transition,
          ancestry: currentAncestry,
        });
      }
      continue;
    }

    if (item.kind === 'transition') {
      const transition = item.transition;
      const targetRef = transition?.toNodeRef;
      const target = model.nodeByRef.get(targetRef);
      const explanation = model.transitionExplanationByRef.get(transition?.transitionRef);
      rows.push({
        kind: 'transition',
        depth: item.depth,
        transition,
        target,
        explanation,
        evidence: evidenceForOwner(model, transition?.transitionRef, explanation?.evidenceRefs || transition?.evidenceRefs),
      });
      if (!target) {
        rows.push({ kind: 'missing-target', depth: item.depth + 1, label: targetRef || 'missing target' });
      } else if (item.ancestry.includes(targetRef)) {
        rows.push({ kind: 'cycle', depth: item.depth + 1, node: target });
      } else if (renderedNodes.has(targetRef)) {
        rows.push({ kind: 'shared', depth: item.depth + 1, node: target });
      } else {
        stack.push({
          kind: 'node',
          nodeRef: targetRef,
          depth: item.depth + 1,
          ancestry: item.ancestry,
        });
      }
      continue;
    }

    if (item.kind === 'boundary') {
      const boundary = item.boundary;
      const explanation = model.boundaryExplanationByRef.get(boundary?.boundaryRef);
      rows.push({
        kind: 'boundary',
        depth: item.depth,
        boundary,
        explanation,
        evidence: evidenceForOwner(model, boundary?.boundaryRef, explanation?.evidenceRefs || boundary?.evidenceRefs),
      });
    }
  }

  return rows;
}

export function evidenceForOwner(model, ownerRef, preferredRefs = []) {
  const refs = [];
  for (const item of list(model.evidenceByOwnerRef.get(ownerRef))) {
    addUnique(refs, item.evidenceRef);
  }
  for (const ref of list(preferredRefs)) {
    const item = model.evidenceByRef.get(ref);
    if (item?.ownerRef === ownerRef) {
      addUnique(refs, ref);
    }
  }
  return refs.map((ref) => model.evidenceByRef.get(ref)).filter(Boolean);
}

export function flowCountDiagnostic(response = {}) {
  return list(response.diagnostics).find((item) => item?.code === 'ENTRYPOINT_FLOW_MAX_FLOWS_REACHED') || null;
}
