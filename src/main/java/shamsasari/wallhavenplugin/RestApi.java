package shamsasari.wallhavenplugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.List;

public class RestApi {
    public record SearchResult(List<WallpaperInfo> data, Meta meta) {}

    public record WallpaperInfo(String id) {}

    public record Meta(
            @JsonProperty("current_page")
            int currentPage,
            @JsonProperty("last_page")
            int lastPage,
            String seed
    ) {}

    public record WallpaperResult(Wallpaper data) {}

    public record Wallpaper(
            String id,
            String url,
            URI path,
            @JsonSetter(nulls = Nulls.AS_EMPTY)
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
