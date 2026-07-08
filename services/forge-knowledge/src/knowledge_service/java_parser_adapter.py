from __future__ import annotations

import hashlib
from typing import Any, Dict, Iterable, List, Optional, Tuple

from tree_sitter import Language, Parser
import tree_sitter_java

from knowledge_service.structural_model import (
    StructuralAnnotation,
    StructuralCallable,
    StructuralCallsite,
    StructuralField,
    StructuralFileMetadata,
    StructuralImport,
    StructuralParseDiagnostic,
    StructuralParseResult,
    StructuralType,
)
from knowledge_service.graph_call_intelligence import ResolutionReason, UnresolvedReason


TYPE_NODE_KINDS = {
    "class_declaration": "CLASS",
    "interface_declaration": "INTERFACE",
    "enum_declaration": "ENUM",
    "record_declaration": "RECORD",
    "annotation_type_declaration": "ANNOTATION",
}

CALLSITE_NODE_TYPES = {
    "method_invocation",
    "super_method_invocation",
    "object_creation_expression",
    "explicit_constructor_invocation",
}


class JavaParserAdapter:
    language = "java"

    def __init__(self) -> None:
        parser = Parser()
        parser.set_language(Language(tree_sitter_java.language(), "java"))
        self.parser = parser

    def parse(self, text: str, file: StructuralFileMetadata) -> StructuralParseResult:
        content = text.encode("utf-8")
        tree = self.parser.parse(content)
        package_name = self._package_name(tree.root_node, content)
        imports = self._imports(tree.root_node, content, file)
        diagnostics: List[StructuralParseDiagnostic] = []
        if tree.root_node.has_error:
            diagnostics.append(
                StructuralParseDiagnostic(
                    code="STRUCTURAL_PARSE_HAS_ERRORS",
                    message="Java parser reported syntax errors; extracted facts may be partial.",
                    severity="WARN",
                )
            )
        types: List[StructuralType] = []
        callables: List[StructuralCallable] = []
        fields: List[StructuralField] = []
        annotations: List[StructuralAnnotation] = []
        callable_bodies: List[Tuple[StructuralCallable, Any]] = []
        self._parse_types(
            tree.root_node,
            content,
            file,
            package_name,
            None,
            None,
            types,
            callables,
            fields,
            annotations,
            callable_bodies,
        )
        callsites = self._callsites(content, file, types, callables, fields, callable_bodies, imports)
        return StructuralParseResult(
            file=file,
            package_name=package_name,
            imports=imports,
            types=types,
            callables=callables,
            fields=fields,
            annotations=annotations,
            callsites=callsites,
            diagnostics=diagnostics,
        )

    def _package_name(self, root, content: bytes) -> Optional[str]:
        for child in root.named_children:
            if child.type != "package_declaration":
                continue
            name = self._first_named_text(child, content, {"identifier", "scoped_identifier"})
            return name
        return None

    def _imports(self, root, content: bytes, file: StructuralFileMetadata) -> List[StructuralImport]:
        imports: List[StructuralImport] = []
        for child in root.named_children:
            if child.type != "import_declaration":
                continue
            imported = self._first_named_text(child, content, {"identifier", "scoped_identifier", "asterisk"})
            if not imported:
                continue
            is_static = any(grand.type == "static" for grand in child.children)
            is_wildcard = imported.endswith(".*") or any(grand.type == "asterisk" for grand in child.children)
            line_start, line_end = self._line_range(child)
            stable_key = self._stable_key(file.source_id, file.relative_path, "IMPORT", imported)
            imports.append(
                StructuralImport(
                    imported_name=imported,
                    is_static=is_static,
                    is_wildcard=is_wildcard,
                    line_start=line_start,
                    line_end=line_end,
                    stable_key=stable_key,
                )
            )
        return imports

    def _parse_types(
        self,
        container,
        content: bytes,
        file: StructuralFileMetadata,
        package_name: Optional[str],
        parent_type: Optional[StructuralType],
        owner_prefix: Optional[str],
        types: List[StructuralType],
        callables: List[StructuralCallable],
        fields: List[StructuralField],
        annotations: List[StructuralAnnotation],
        callable_bodies: List[Tuple[StructuralCallable, Any]],
    ) -> None:
        for node in container.named_children:
            if node.type not in TYPE_NODE_KINDS:
                continue
            name_node = node.child_by_field_name("name")
            if name_node is None:
                continue
            name = self._text(name_node, content)
            qualified = ".".join(part for part in [package_name, owner_prefix, name] if part)
            line_start, line_end = self._line_range(node)
            body = node.child_by_field_name("body")
            body_line_start, body_line_end = self._body_line_range(body)
            local_id = self._stable_key(file.source_id, file.relative_path, "TYPE", qualified)
            node_annotations = self._annotations(node, content, local_id)
            structural_type = StructuralType(
                local_id=local_id,
                name=name,
                qualified_name=qualified,
                type_kind=TYPE_NODE_KINDS.get(node.type, "UNKNOWN"),
                line_start=line_start,
                line_end=line_end,
                body_line_start=body_line_start,
                body_line_end=body_line_end,
                annotations=node_annotations,
                parent_type_local_id=parent_type.local_id if parent_type else None,
                stable_key=local_id,
            )
            types.append(structural_type)
            annotations.extend(node_annotations)
            if body is None:
                continue
            self._parse_type_members(body, content, file, structural_type, types, callables, fields, annotations, callable_bodies)
            self._parse_types(
                body,
                content,
                file,
                package_name,
                structural_type,
                ".".join(part for part in [owner_prefix, name] if part),
                types,
                callables,
                fields,
                annotations,
                callable_bodies,
            )

    def _parse_type_members(
        self,
        body,
        content: bytes,
        file: StructuralFileMetadata,
        owner: StructuralType,
        types: List[StructuralType],
        callables: List[StructuralCallable],
        fields: List[StructuralField],
        annotations: List[StructuralAnnotation],
        callable_bodies: List[Tuple[StructuralCallable, Any]],
    ) -> None:
        for node in body.named_children:
            if node.type == "field_declaration":
                fields.extend(self._field_declaration(node, content, file, owner, annotations))
            elif node.type in {"method_declaration", "constructor_declaration", "compact_constructor_declaration"}:
                callable_item = self._callable(node, content, file, owner)
                if callable_item:
                    callables.append(callable_item)
                    annotations.extend(callable_item.annotations)
                    callable_body = node.child_by_field_name("body")
                    if callable_body is not None:
                        callable_bodies.append((callable_item, callable_body))

    def _field_declaration(
        self, node, content: bytes, file: StructuralFileMetadata, owner: StructuralType, annotations: List[StructuralAnnotation]
    ) -> List[StructuralField]:
        type_node = node.child_by_field_name("type")
        type_name = self._text(type_node, content) if type_node is not None else None
        visibility = self._visibility(node)
        line_start, line_end = self._line_range(node)
        result: List[StructuralField] = []
        for declarator in [child for child in node.named_children if child.type == "variable_declarator"]:
            name_node = declarator.child_by_field_name("name")
            if name_node is None:
                continue
            name = self._text(name_node, content)
            qualified = f"{owner.qualified_name}.{name}"
            local_id = self._stable_key(file.source_id, file.relative_path, "FIELD", owner.qualified_name, name)
            field_annotations = self._annotations(node, content, local_id)
            annotations.extend(field_annotations)
            result.append(
                StructuralField(
                    local_id=local_id,
                    name=name,
                    qualified_name=qualified,
                    owner_type_local_id=owner.local_id,
                    type_name=type_name,
                    line_start=line_start,
                    line_end=line_end,
                    annotations=field_annotations,
                    visibility=visibility,
                    stable_key=local_id,
                )
            )
        return result

    def _callable(self, node, content: bytes, file: StructuralFileMetadata, owner: StructuralType) -> Optional[StructuralCallable]:
        name_node = node.child_by_field_name("name")
        if name_node is None:
            name = owner.name
        else:
            name = self._text(name_node, content)
        callable_kind = "CONSTRUCTOR" if node.type in {"constructor_declaration", "compact_constructor_declaration"} else "METHOD"
        parameters_node = node.child_by_field_name("parameters")
        parameters, parameter_names = self._parameter_details(parameters_node, content)
        signature = f"{name}({','.join(parameters)})"
        return_type_node = node.child_by_field_name("type")
        return_type = None if callable_kind == "CONSTRUCTOR" or return_type_node is None else self._text(return_type_node, content)
        line_start, line_end = self._line_range(node)
        body = node.child_by_field_name("body")
        body_line_start, body_line_end = self._body_line_range(body)
        local_id = self._stable_key(file.source_id, file.relative_path, "CALLABLE", owner.qualified_name, name, signature)
        return StructuralCallable(
            local_id=local_id,
            name=name,
            qualified_name=f"{owner.qualified_name}.{name}",
            callable_kind=callable_kind,
            owner_type_local_id=owner.local_id,
            signature=signature,
            return_type=return_type,
            parameters=parameters,
            line_start=line_start,
            line_end=line_end,
            body_line_start=body_line_start,
            body_line_end=body_line_end,
            annotations=self._annotations(node, content, local_id),
            visibility=self._visibility(node),
            is_static=self._has_modifier(node, "static"),
            stable_key=local_id,
            parameter_names=parameter_names,
        )

    def _callsites(
        self,
        content: bytes,
        file: StructuralFileMetadata,
        types: List[StructuralType],
        callables: List[StructuralCallable],
        fields: List[StructuralField],
        callable_bodies: List[Tuple[StructuralCallable, Any]],
        imports: List[StructuralImport],
    ) -> List[StructuralCallsite]:
        by_owner: Dict[str, List[StructuralCallable]] = {}
        by_type_name: Dict[str, StructuralType] = {}
        for item in types:
            by_type_name[item.name] = item
            by_type_name[item.qualified_name] = item
        for item in callables:
            if item.owner_type_local_id:
                by_owner.setdefault(item.owner_type_local_id, []).append(item)
        fields_by_owner_name: Dict[Tuple[str, str], StructuralField] = {}
        for field in fields:
            fields_by_owner_name[(field.owner_type_local_id, field.name)] = field
        imported_simple_names = {self._simple_type(item.imported_name) for item in imports if not item.is_wildcard}
        callsites: List[StructuralCallsite] = []
        for caller, body in callable_bodies:
            parameter_types = {name: self._simple_type(type_name) for name, type_name in zip(caller.parameter_names, caller.parameters) if name and type_name}
            local_variable_types = self._local_variable_types(body, content)
            for node in self._descendants(body):
                if node.type not in CALLSITE_NODE_TYPES:
                    continue
                callsite = self._callsite(
                    node,
                    content,
                    file,
                    caller,
                    by_owner,
                    by_type_name,
                    fields_by_owner_name,
                    parameter_types,
                    local_variable_types,
                    imported_simple_names,
                )
                if callsite:
                    callsites.append(callsite)
        return callsites

    def _callsite(
        self,
        node,
        content: bytes,
        file: StructuralFileMetadata,
        caller: StructuralCallable,
        by_owner: Dict[str, List[StructuralCallable]],
        by_type_name: Dict[str, StructuralType],
        fields_by_owner_name: Dict[Tuple[str, str], StructuralField],
        parameter_types: Dict[str, str],
        local_variable_types: Dict[str, str],
        imported_simple_names: set[str],
    ) -> Optional[StructuralCallsite]:
        line_start, line_end = self._line_range(node)
        receiver_text: Optional[str] = None
        receiver_type_hint: Optional[str] = None
        target_type_text: Optional[str] = None
        target_callable_local_id: Optional[str] = None
        field_receiver_local_id: Optional[str] = None
        resolution_status = "UNRESOLVED"
        unresolved_reason: Optional[str] = None
        resolution_reason: Optional[str] = None
        argument_count = self._argument_count(node.child_by_field_name("arguments"))
        call_kind = "LOCAL_METHOD"
        if node.type == "object_creation_expression":
            type_node = node.child_by_field_name("type")
            if type_node is None:
                return None
            method_name = self._text(type_node, content)
            target_type_text = self._simple_type(method_name)
            call_kind = "CONSTRUCTOR"
            target_callable_local_id, resolution_status = self._resolve_constructor(target_type_text, argument_count, by_owner, by_type_name)
            resolution_reason = ResolutionReason.QUALIFIED_NAME_MATCH.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
            unresolved_reason = None if resolution_status == "RESOLVED" else UnresolvedReason.TARGET_NOT_ANALYZED.value
        elif node.type == "explicit_constructor_invocation":
            method_name = "this"
            receiver_text = "this"
            call_kind = "THIS_METHOD"
            unresolved_reason = UnresolvedReason.DYNAMIC_DISPATCH.value
        else:
            name_node = node.child_by_field_name("name")
            if name_node is None:
                return None
            method_name = self._text(name_node, content)
            receiver_node = node.child_by_field_name("object")
            receiver_text = self._text(receiver_node, content) if receiver_node is not None else None
            if node.type == "super_method_invocation" or receiver_text == "super":
                call_kind = "SUPER_METHOD"
                resolution_status = "UNRESOLVED"
                unresolved_reason = UnresolvedReason.DYNAMIC_DISPATCH.value
            elif not receiver_text or receiver_text == "this":
                call_kind = "THIS_METHOD" if receiver_text == "this" else "LOCAL_METHOD"
                target_callable_local_id, resolution_status = self._resolve_same_owner(caller, method_name, argument_count, by_owner)
                resolution_reason = ResolutionReason.SAME_TYPE_METHOD.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.NO_MATCH.value)
                if resolution_status == "UNRESOLVED" and method_name in imported_simple_names:
                    resolution_status = "EXTERNAL_TARGET"
                    target_type_text = method_name
                    resolution_reason = ResolutionReason.STATIC_IMPORT_MATCH.value
                    unresolved_reason = UnresolvedReason.EXTERNAL_NOT_MODELED.value
            else:
                receiver_simple = self._simple_type(receiver_text)
                explicit_field = self._explicit_this_field(receiver_text, caller.owner_type_local_id, fields_by_owner_name)
                implicit_field = self._implicit_field(receiver_text, caller.owner_type_local_id, fields_by_owner_name)
                if explicit_field is not None:
                    call_kind = "FIELD_RECEIVER"
                    field_receiver_local_id = explicit_field.local_id
                    field_type = self._simple_type(explicit_field.type_name or "")
                    receiver_type_hint = field_type
                    target_type_text = field_type
                    target_callable_local_id, resolution_status = self._resolve_type_method(field_type, method_name, argument_count, by_owner, by_type_name)
                    resolution_reason = ResolutionReason.FIELD_TYPE_HINT.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                    unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.TARGET_NOT_ANALYZED.value)
                elif receiver_text in parameter_types:
                    call_kind = "PARAMETER_RECEIVER"
                    receiver_type_hint = parameter_types[receiver_text]
                    target_type_text = receiver_type_hint
                    target_callable_local_id, resolution_status = self._resolve_type_method(
                        receiver_type_hint, method_name, argument_count, by_owner, by_type_name
                    )
                    resolution_reason = ResolutionReason.PARAMETER_TYPE_HINT.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                    unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.TARGET_NOT_ANALYZED.value)
                elif receiver_text in local_variable_types:
                    call_kind = "LOCAL_VARIABLE_RECEIVER"
                    receiver_type_hint = local_variable_types[receiver_text]
                    target_type_text = receiver_type_hint
                    target_callable_local_id, resolution_status = self._resolve_type_method(
                        receiver_type_hint, method_name, argument_count, by_owner, by_type_name
                    )
                    resolution_reason = (
                        ResolutionReason.LOCAL_VARIABLE_TYPE_HINT.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                    )
                    unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.TARGET_NOT_ANALYZED.value)
                elif implicit_field is not None and implicit_field.type_name:
                    field_type = self._simple_type(implicit_field.type_name)
                    call_kind = "FIELD_RECEIVER"
                    field_receiver_local_id = implicit_field.local_id
                    receiver_type_hint = field_type
                    target_type_text = field_type
                    target_callable_local_id, resolution_status = self._resolve_type_method(field_type, method_name, argument_count, by_owner, by_type_name)
                    resolution_reason = ResolutionReason.FIELD_TYPE_HINT.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                    unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.TARGET_NOT_ANALYZED.value)
                elif receiver_simple in by_type_name:
                    call_kind = "STATIC_METHOD"
                    target_type_text = receiver_simple
                    target_callable_local_id, resolution_status = self._resolve_type_method(
                        receiver_simple, method_name, argument_count, by_owner, by_type_name
                    )
                    resolution_reason = ResolutionReason.QUALIFIED_NAME_MATCH.value if resolution_status == "RESOLVED" else ResolutionReason.NOT_RESOLVED.value
                    unresolved_reason = self._unresolved_reason_from_status(resolution_status, UnresolvedReason.TARGET_NOT_ANALYZED.value)
                elif receiver_simple in imported_simple_names or receiver_simple[:1].isupper():
                    call_kind = "STATIC_METHOD"
                    target_type_text = receiver_simple
                    resolution_status = "EXTERNAL_TARGET"
                    resolution_reason = ResolutionReason.EXTERNAL_PACKAGE_CLASSIFICATION.value
                    unresolved_reason = UnresolvedReason.EXTERNAL_NOT_MODELED.value
                elif "." in receiver_text or receiver_text.endswith(")"):
                    call_kind = "CHAINED_CALL"
                    unresolved_reason = UnresolvedReason.CHAINED_CALL_TARGET_UNKNOWN.value
                else:
                    call_kind = "LOCAL_VARIABLE_RECEIVER"
                    unresolved_reason = UnresolvedReason.LOCAL_VARIABLE_TYPE_UNKNOWN.value
        raw_text = self._text(node, content)
        stable_key = self._stable_key(
            file.source_id,
            file.relative_path,
            "CALLSITE",
            caller.stable_key,
            str(line_start),
            str(node.start_byte),
            str(node.end_byte),
            method_name,
            receiver_text or "",
        )
        return StructuralCallsite(
            local_id=stable_key,
            caller_callable_local_id=caller.local_id,
            receiver_text=receiver_text,
            receiver_type_hint=receiver_type_hint,
            method_name=method_name,
            argument_count=argument_count,
            target_type_text=target_type_text,
            call_kind=call_kind,
            line_start=line_start,
            line_end=line_end,
            raw_text=raw_text[:500],
            resolution_status=resolution_status,
            stable_key=stable_key,
            target_callable_local_id=target_callable_local_id,
            unresolved_reason=unresolved_reason,
            resolution_reason=resolution_reason,
            owner_type_hint=caller.qualified_name.rsplit(".", 1)[0] if "." in caller.qualified_name else caller.qualified_name,
            field_receiver_local_id=field_receiver_local_id,
        )

    def _explicit_this_field(
        self,
        receiver_text: Optional[str],
        owner_type_local_id: Optional[str],
        fields_by_owner_name: Dict[Tuple[str, str], StructuralField],
    ) -> Optional[StructuralField]:
        if not receiver_text or not owner_type_local_id:
            return None
        if not receiver_text.startswith("this."):
            return None
        field_name = receiver_text[len("this.") :].split(".", 1)[0]
        return fields_by_owner_name.get((owner_type_local_id, field_name))

    def _implicit_field(
        self,
        receiver_text: Optional[str],
        owner_type_local_id: Optional[str],
        fields_by_owner_name: Dict[Tuple[str, str], StructuralField],
    ) -> Optional[StructuralField]:
        if not receiver_text or not owner_type_local_id or "." in receiver_text or receiver_text.endswith(")"):
            return None
        return fields_by_owner_name.get((owner_type_local_id, receiver_text))

    def _resolve_same_owner(
        self, caller: StructuralCallable, method_name: str, argument_count: Optional[int], by_owner: Dict[str, List[StructuralCallable]]
    ) -> Tuple[Optional[str], str]:
        candidates = [item for item in by_owner.get(caller.owner_type_local_id or "", []) if item.name == method_name]
        return self._resolve_candidates(candidates, argument_count)

    def _resolve_type_method(
        self,
        type_name: str,
        method_name: str,
        argument_count: Optional[int],
        by_owner: Dict[str, List[StructuralCallable]],
        by_type_name: Dict[str, StructuralType],
    ) -> Tuple[Optional[str], str]:
        target_type = by_type_name.get(type_name)
        if target_type is None:
            return None, "UNRESOLVED"
        candidates = [item for item in by_owner.get(target_type.local_id, []) if item.name == method_name]
        return self._resolve_candidates(candidates, argument_count)

    def _resolve_constructor(
        self, type_name: str, argument_count: Optional[int], by_owner: Dict[str, List[StructuralCallable]], by_type_name: Dict[str, StructuralType]
    ) -> Tuple[Optional[str], str]:
        target_type = by_type_name.get(type_name)
        if target_type is None:
            return None, "EXTERNAL_TARGET"
        candidates = [item for item in by_owner.get(target_type.local_id, []) if item.callable_kind == "CONSTRUCTOR"]
        if not candidates:
            return None, "UNRESOLVED"
        return self._resolve_candidates(candidates, argument_count)

    def _resolve_candidates(self, candidates: List[StructuralCallable], argument_count: Optional[int]) -> Tuple[Optional[str], str]:
        if not candidates:
            return None, "UNRESOLVED"
        if argument_count is not None:
            matching = [item for item in candidates if len(item.parameters) == argument_count]
            if len(matching) == 1:
                return matching[0].local_id, "RESOLVED"
            if len(matching) > 1:
                return None, "MULTIPLE_CANDIDATES"
        if len(candidates) == 1:
            return candidates[0].local_id, "RESOLVED"
        return None, "MULTIPLE_CANDIDATES"

    def _unresolved_reason_from_status(self, status: str, default: str) -> Optional[str]:
        if status == "RESOLVED":
            return None
        if status == "MULTIPLE_CANDIDATES":
            return UnresolvedReason.MULTIPLE_METHODS_MATCH.value
        return default

    def _annotations(self, node, content: bytes, target_local_id: Optional[str]) -> List[StructuralAnnotation]:
        result: List[StructuralAnnotation] = []
        modifiers = next((child for child in node.named_children if child.type == "modifiers"), None)
        if modifiers is None:
            return result
        for child in modifiers.named_children:
            if child.type not in {"marker_annotation", "annotation"}:
                continue
            name_node = child.child_by_field_name("name")
            if name_node is None:
                continue
            args = child.child_by_field_name("arguments")
            line_start, line_end = self._line_range(child)
            result.append(
                StructuralAnnotation(
                    name=self._text(name_node, content),
                    arguments_raw=self._text(args, content) if args is not None else None,
                    line_start=line_start,
                    line_end=line_end,
                    target_local_id=target_local_id,
                )
            )
        return result

    def _parameter_details(self, node, content: bytes) -> Tuple[List[str], List[str]]:
        if node is None:
            return [], []
        types: List[str] = []
        names: List[str] = []
        for child in node.named_children:
            if child.type not in {"formal_parameter", "spread_parameter", "receiver_parameter"}:
                continue
            type_node = child.child_by_field_name("type")
            if type_node is not None:
                types.append(self._text(type_node, content))
                name_node = child.child_by_field_name("name")
                names.append(self._text(name_node, content) if name_node is not None else "")
        return types, names

    def _local_variable_types(self, body, content: bytes) -> Dict[str, str]:
        result: Dict[str, str] = {}
        for node in self._descendants(body):
            if node.type != "local_variable_declaration":
                continue
            type_node = node.child_by_field_name("type")
            type_name = self._text(type_node, content) if type_node is not None else None
            for declarator in [child for child in node.named_children if child.type == "variable_declarator"]:
                name_node = declarator.child_by_field_name("name")
                if name_node is None:
                    continue
                resolved_type = type_name
                if resolved_type == "var":
                    value_node = declarator.child_by_field_name("value")
                    if value_node is not None and value_node.type == "object_creation_expression":
                        created_type = value_node.child_by_field_name("type")
                        resolved_type = self._text(created_type, content) if created_type is not None else None
                if resolved_type and resolved_type != "var":
                    result[self._text(name_node, content)] = self._simple_type(resolved_type)
        return result

    def _argument_count(self, node) -> Optional[int]:
        if node is None:
            return None
        return len(node.named_children)

    def _visibility(self, node) -> Optional[str]:
        for value in ("public", "protected", "private"):
            if self._has_modifier(node, value):
                return value.upper()
        return None

    def _has_modifier(self, node, modifier: str) -> bool:
        modifiers = next((child for child in node.children if child.type == "modifiers"), None)
        if modifiers is None:
            return False
        return any(child.type == modifier for child in modifiers.children)

    def _descendants(self, node) -> Iterable[Any]:
        stack = list(reversed(node.named_children))
        while stack:
            current = stack.pop()
            yield current
            if current.type in {"class_declaration", "interface_declaration", "enum_declaration", "record_declaration", "annotation_type_declaration"}:
                continue
            stack.extend(reversed(current.named_children))

    def _first_named_text(self, node, content: bytes, types: set[str]) -> Optional[str]:
        for child in node.named_children:
            if child.type in types:
                return self._text(child, content)
        return None

    def _line_range(self, node) -> Tuple[int, int]:
        start = int(node.start_point[0]) + 1
        end = int(node.end_point[0]) + 1
        return start, max(start, end)

    def _body_line_range(self, body) -> Tuple[Optional[int], Optional[int]]:
        if body is None:
            return None, None
        return self._line_range(body)

    def _text(self, node, content: bytes) -> str:
        if node is None:
            return ""
        return content[node.start_byte : node.end_byte].decode("utf-8", errors="replace").strip()

    def _simple_type(self, value: str) -> str:
        value = value.strip()
        value = value.split("<", 1)[0]
        return value.rsplit(".", 1)[-1]

    def _stable_key(self, *parts: str) -> str:
        return "|".join(str(part) for part in parts if part is not None)

    def _stable_id(self, *parts: str) -> str:
        digest = hashlib.sha256("\0".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:24]
        return f"{parts[0]}:{digest}"
