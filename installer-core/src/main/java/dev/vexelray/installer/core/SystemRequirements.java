package dev.vexelray.installer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the computer must have. The installer checks it (with {@code diagnose.ps1}) before downloading
 * anything large and refuses when something is not met, unless told to skip the check. The GPU fields are met
 * together, by one GPU: its Vulkan device has the version and the extensions, and the DXGI adapter with the same
 * LUID has the Direct3D 12 feature level.
 */
public final class SystemRequirements {
    /** Oldest Windows version, as {@code 10.0.19041} (or just the build, {@code 19041}). */
    public String windows;
    public Integer memoryMb;
    /** Free space on the drive of the install folder. */
    public Integer diskMb;
    /** Oldest Vulkan API version a GPU must report, as {@code 1.3}. */
    public String vulkan;
    public List<String> vulkanExtensions = new ArrayList<>();
    /** Lowest Direct3D 12 feature level: {@code 11_0}, {@code 11_1}, {@code 12_0}, {@code 12_1} or {@code 12_2}. */
    public String d3d12;

    static final Map<String, Integer> D3D12_LEVELS = Map.of("11_0", 0xb000, "11_1", 0xb100, "12_0", 0xc000, "12_1", 0xc100, "12_2", 0xc200);

    boolean isEmpty() {
        return InstallerConfig.blank(windows) && memoryMb == null && diskMb == null && InstallerConfig.blank(vulkan)
                && (vulkanExtensions == null || vulkanExtensions.isEmpty()) && InstallerConfig.blank(d3d12);
    }

    void check() {
        if (!InstallerConfig.blank(windows) && !windows.trim().matches("\\d+(\\.\\d+){0,2}"))
            throw new InstallerException("system.windows is a Windows version such as 10.0.19041, not '" + windows + "'");
        if (memoryMb != null && memoryMb <= 0) throw new InstallerException("system.memoryMb must be positive");
        if (diskMb != null && diskMb <= 0) throw new InstallerException("system.diskMb must be positive");
        if (!InstallerConfig.blank(vulkan) && !vulkan.trim().matches("1\\.\\d+"))
            throw new InstallerException("system.vulkan is a Vulkan version such as 1.3, not '" + vulkan + "'");
        if (vulkanExtensions != null) for (String e : vulkanExtensions)
            if (e == null || !e.matches("VK_[A-Za-z0-9_]+"))
                throw new InstallerException("system.vulkanExtensions are extension names such as VK_KHR_swapchain, not '" + e + "'");
        if (!InstallerConfig.blank(d3d12) && !D3D12_LEVELS.containsKey(d3d12.trim()))
            throw new InstallerException("system.d3d12 is a feature level: 11_0, 11_1, 12_0, 12_1 or 12_2, not '" + d3d12 + "'");
    }

    /** {@code 10.0.19041} from {@code 19041}, so the script compares like with like. */
    String windowsVersion() {
        String w = windows.trim();
        return w.contains(".") ? w : "10.0." + w;
    }

    /** One line per requirement, for the README. */
    List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (!InstallerConfig.blank(windows)) lines.add("Windows " + windowsVersion() + " or newer");
        if (memoryMb != null) lines.add(memoryMb + " MB of memory");
        if (diskMb != null) lines.add(diskMb + " MB free on the drive it is installed to");
        List<String> gpu = new ArrayList<>();
        if (!InstallerConfig.blank(vulkan)) gpu.add("Vulkan " + vulkan.trim());
        if (vulkanExtensions != null && !vulkanExtensions.isEmpty())
            gpu.add((gpu.isEmpty() ? "Vulkan with " : "with ") + String.join(", ", vulkanExtensions.stream().map(e -> "`" + e + "`").toList()));
        if (!InstallerConfig.blank(d3d12)) gpu.add((gpu.isEmpty() ? "" : "and ") + "Direct3D 12 feature level " + d3d12.trim());
        if (!gpu.isEmpty()) lines.add("a GPU with " + String.join(" ", gpu) + (gpu.size() > 1 ? ", all on the same GPU" : ""));
        return lines;
    }
}
