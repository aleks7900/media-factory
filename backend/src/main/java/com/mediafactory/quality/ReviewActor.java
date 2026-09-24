package com.mediafactory.quality;
import org.springframework.stereotype.Component;
/** Authentication integration boundary. This deployment is a private, unauthenticated local workspace. */
@Component
public class ReviewActor {
 public String current(){return "local-workspace";}
}
