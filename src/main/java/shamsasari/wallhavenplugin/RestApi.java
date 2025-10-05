package shamsasari.wallhavenplugin;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.List;

public class RestApi {
    public record SearchResult(List<WallpaperInfo> data, Meta meta) {}

    public record WallpaperInfo(String id, String url) {}

    public record Meta(
            int currentPage,
            int lastPage,
            String seed
    ) {}

    public record WallpaperResult(Wallpaper data) {}

    public record Wallpaper(
            String id,
            String url,
            URI path,
            List<Tag> tags
    ) {}

    public record Tag(String name) {
        @Nonnull
        @Override
        public String toString() {
            return name;
        }
    }
}
