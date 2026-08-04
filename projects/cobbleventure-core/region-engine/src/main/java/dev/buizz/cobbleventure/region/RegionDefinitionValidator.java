package dev.buizz.cobbleventure.region;

import dev.buizz.cobbleventure.api.BoundaryType;
import dev.buizz.cobbleventure.api.RegionConnection;
import dev.buizz.cobbleventure.api.RegionDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class RegionDefinitionValidator {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    public List<ValidationIssue> validate(RegionDefinition region) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (region.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            issues.add(issue("schema_version", "지원하는 스키마 버전은 1입니다."));
        }
        validateResourceId("id", region.id(), issues);
        validateResourceId("dimension", region.dimension(), issues);

        if (region.bounds() == null) {
            issues.add(issue("bounds", "지역 경계가 필요합니다."));
        } else if (region.bounds().minX() > region.bounds().maxX()
            || region.bounds().minZ() > region.bounds().maxZ()) {
            issues.add(issue("bounds", "최소 좌표는 최대 좌표보다 클 수 없습니다."));
        }

        if (region.biomePool() == null || region.biomePool().isEmpty()) {
            issues.add(issue("biome_pool", "바이옴 풀은 하나 이상의 항목을 가져야 합니다."));
        } else {
            for (int index = 0; index < region.biomePool().size(); index++) {
                validateResourceId("biome_pool[" + index + "]", region.biomePool().get(index), issues);
            }
        }

        validateBoundary(region, issues);
        validateConnections(region, issues);
        validateAnchors(region, issues);
        if (region.spawnProfile() != null) {
            region.spawnProfile().ifPresent(value -> validateResourceId("spawn_profile", value, issues));
        }
        return List.copyOf(issues);
    }

    private void validateBoundary(RegionDefinition region, List<ValidationIssue> issues) {
        if (region.boundary() == null || region.boundary().type() == null) {
            issues.add(issue("boundary", "경계 방식이 필요합니다."));
            return;
        }

        BoundaryType type = region.boundary().type();
        if ((type == BoundaryType.STRUCTURE_WALL || type == BoundaryType.COMBINED)
            && (region.boundary().template() == null || region.boundary().template().isEmpty())) {
            issues.add(issue("boundary.template", "구조물 경계에는 템플릿이 필요합니다."));
        }
        if (region.boundary().template() != null) {
            region.boundary().template().ifPresent(value -> validateResourceId("boundary.template", value, issues));
        }
        validateResourceId("boundary.protection_profile", region.boundary().protectionProfile(), issues);
    }

    private void validateConnections(RegionDefinition region, List<ValidationIssue> issues) {
        if (region.connections() == null) {
            issues.add(issue("connections", "연결 목록은 생략하지 말고 빈 배열로 지정해야 합니다."));
            return;
        }

        Set<String> gateIds = new HashSet<>();
        for (int index = 0; index < region.connections().size(); index++) {
            RegionConnection connection = region.connections().get(index);
            String basePath = "connections[" + index + "]";
            if (connection == null) {
                issues.add(issue(basePath, "연결 정의는 null일 수 없습니다."));
                continue;
            }
            validateResourceId(basePath + ".target", connection.target(), issues);
            validateResourceId(basePath + ".gate_id", connection.gateId(), issues);
            if (connection.requirement() != null) {
                connection.requirement().ifPresent(value -> validateResourceId(basePath + ".requirement", value, issues));
            }

            if (connection.target() != null && connection.target().equals(region.id())) {
                issues.add(issue(basePath + ".target", "지역은 자기 자신과 연결할 수 없습니다."));
            }
            if (!gateIds.add(connection.gateId())) {
                issues.add(issue(basePath + ".gate_id", "한 지역에서 관문 ID는 중복될 수 없습니다."));
            }
        }
    }

    private void validateAnchors(RegionDefinition region, List<ValidationIssue> issues) {
        if (region.anchors() == null || region.bounds() == null) {
            return;
        }
        region.anchors().forEach((name, position) -> {
            if (name == null || name.isBlank()) {
                issues.add(issue("anchors", "앵커 이름은 비어 있을 수 없습니다."));
            }
            if (position == null || !region.bounds().contains(position)) {
                issues.add(issue("anchors." + name, "앵커는 지역 경계 안에 있어야 합니다."));
            }
        });
    }

    private void validateResourceId(String path, String value, List<ValidationIssue> issues) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) {
            issues.add(issue(path, "namespace:path 형식의 소문자 리소스 ID가 필요합니다."));
        }
    }

    private ValidationIssue issue(String path, String message) {
        return new ValidationIssue(path, message);
    }
}
