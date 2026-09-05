export interface UserProfile {
  id: number;
  nickname: string;
  avatarEmoji: string | null;
  occupation: string | null;
  learningDirection: string | null;
  learningSkillId: string | null;
  currentLevel: string | null;
  learningGoal: string | null;
  createdAt: string;
}

export interface SaveUserPayload {
  nickname: string;
  avatarEmoji?: string;
  occupation?: string;
  learningDirection?: string;
  learningSkillId?: string;
  currentLevel?: string;
  learningGoal?: string;
}
