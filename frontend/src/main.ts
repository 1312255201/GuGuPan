import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import HomeView from "./views/HomeView.vue";
import LoginView from "./views/LoginView.vue";
import AdminView from "./views/AdminView.vue";
import TransferView from "./views/TransferView.vue";
import { auth, refreshSession } from "./api";
import "./style.css";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: HomeView },
    { path: "/login", component: LoginView },
    {
      path: "/admin/:tab?",
      component: AdminView,
      meta: { requiresAuth: true },
    },
    { path: "/s/:token", component: TransferView },
    { path: "/r/:token", component: TransferView },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  if (to.meta.requiresAuth) {
    try {
      await refreshSession();
    } catch {
      return "/login";
    }
    if (!auth.authenticated) return "/login";
  }
  if (to.path === "/login") {
    try {
      await refreshSession();
    } catch {
      return true;
    }
    if (auth.authenticated) return "/admin";
  }
});
createApp(App).use(router).mount("#app");
