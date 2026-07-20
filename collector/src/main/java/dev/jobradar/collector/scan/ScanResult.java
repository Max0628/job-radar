package dev.jobradar.collector.scan;

import java.util.List;

public record ScanResult(List<DiscoveredJob> discovered, int pagesScanned) {
}
